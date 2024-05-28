/*******************************************************************************
 * Copyright (c) 2023, Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.jdt.core.dom;

import java.io.File;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.AccessRestriction;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.ISourceType;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.impl.ITypeRequestor;
import org.eclipse.jdt.internal.compiler.lookup.BinaryTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.PackageBinding;
import org.eclipse.jdt.internal.compiler.util.Util;
import org.eclipse.jdt.internal.javac.JavacProblemConverter;
import org.eclipse.jdt.internal.javac.JavacUtils;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.parser.JavadocTokenizer;
import com.sun.tools.javac.parser.Scanner;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.parser.Tokens.Comment.CommentStyle;
import com.sun.tools.javac.parser.Tokens.TokenKind;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DiagnosticSource;

/**
 * Allows to create and resolve DOM ASTs using Javac
 * @implNote Cannot move to another package because parent class is package visible only
 */
class JavacCompilationUnitResolver implements ICompilationUnitResolver {

	@Override
	public void resolve(String[] sourceFilePaths, String[] encodings, String[] bindingKeys, FileASTRequestor requestor,
			int apiLevel, Map<String, String> compilerOptions, List<Classpath> classpaths, int flags,
			IProgressMonitor monitor) {

		// make list of source unit
		int length = sourceFilePaths.length;
		List<org.eclipse.jdt.internal.compiler.env.ICompilationUnit> sourceUnitList = new ArrayList<>(length);
		for (int i = 0; i < length; i++) {
			char[] contents = null;
			String encoding = encodings != null ? encodings[i] : null;
			String sourceUnitPath = sourceFilePaths[i];
			try {
				contents = Util.getFileCharContent(new File(sourceUnitPath), encoding);
			} catch(IOException e) {
				// go to the next unit
				continue;
			}
			if (contents == null) {
				// go to the next unit
				continue;
			}
			sourceUnitList.add(new org.eclipse.jdt.internal.compiler.batch.CompilationUnit(contents, sourceUnitPath, encoding));
		}

		JavacBindingResolver bindingResolver = null;

		// parse source units
		var res = parse(sourceUnitList.toArray(org.eclipse.jdt.internal.compiler.env.ICompilationUnit[]::new), apiLevel, compilerOptions, flags, (IJavaProject)null, monitor);

		for (var entry : res.entrySet()) {
			CompilationUnit cu = entry.getValue();
			requestor.acceptAST(new String(entry.getKey().getFileName()), cu);
			if (bindingResolver == null && (JavacBindingResolver)cu.ast.getBindingResolver() != null) {
				bindingResolver = (JavacBindingResolver)cu.ast.getBindingResolver();
			}
		}

		if (bindingResolver == null) {
			var compiler = ToolProvider.getSystemJavaCompiler();
			var context = new Context();
			JavacTask task = (JavacTask) compiler.getTask(null, null, null, List.of(), List.of(), List.of());
			bindingResolver = new JavacBindingResolver(null, task, context, new JavacConverter(null, null, context, null));
		}

		HashMap<String, IBinding> bindingMap = new HashMap<>();
		for (CompilationUnit cu : res.values()) {
			cu.accept(new BindingBuilder(bindingMap));
		}

		NameEnvironmentWithProgress environment = new NameEnvironmentWithProgress(classpaths.stream().toArray(Classpath[]::new), null, monitor);
		LookupEnvironment lu = new LookupEnvironment(new ITypeRequestor() {

			@Override
			public void accept(IBinaryType binaryType, PackageBinding packageBinding,
					AccessRestriction accessRestriction) {
				// do nothing
			}

			@Override
			public void accept(org.eclipse.jdt.internal.compiler.env.ICompilationUnit unit,
					AccessRestriction accessRestriction) {
				// do nothing
			}

			@Override
			public void accept(ISourceType[] sourceType, PackageBinding packageBinding,
					AccessRestriction accessRestriction) {
				// do nothing
			}

		}, new CompilerOptions(compilerOptions), null, environment);

		// resolve the requested bindings
		for (String bindingKey : bindingKeys) {

			IBinding bindingFromMap = bindingMap.get(bindingKey);
			if (bindingFromMap != null) {
				// from parsed files
				requestor.acceptBinding(bindingKey, bindingFromMap);
			} else {
				// from ECJ
				char[] charArrayFQN = Signature.toCharArray(bindingKey.toCharArray());
				char[][] twoDimensionalCharArrayFQN = Stream.of(new String(charArrayFQN).split("/")) //
						.map(myString -> myString.toCharArray()) //
						.toArray(char[][]::new);

				NameEnvironmentAnswer answer = environment.findType(twoDimensionalCharArrayFQN);
				IBinaryType binaryType = answer.getBinaryType();
				if (binaryType != null) {
					BinaryTypeBinding binding = lu.cacheBinaryType(binaryType, null);
					requestor.acceptBinding(bindingKey, new TypeBinding(bindingResolver, binding));
				}
			}

		}

	}

	@Override
	public void parse(ICompilationUnit[] compilationUnits, ASTRequestor requestor, int apiLevel,
			Map<String, String> compilerOptions, int flags, IProgressMonitor monitor) {
		var units = parse(compilationUnits, apiLevel, compilerOptions, flags, monitor);
		if (requestor != null) {
			units.forEach(requestor::acceptAST);
		}
	}

	private Map<ICompilationUnit, CompilationUnit> parse(ICompilationUnit[] compilationUnits, int apiLevel,
			Map<String, String> compilerOptions, int flags, IProgressMonitor monitor) {
		// TODO ECJCompilationUnitResolver has support for dietParse and ignore method body
		// is this something we need?
		if (compilationUnits.length > 0
			&& Arrays.stream(compilationUnits).map(ICompilationUnit::getJavaProject).distinct().count() == 1
			&& Arrays.stream(compilationUnits).allMatch(org.eclipse.jdt.internal.compiler.env.ICompilationUnit.class::isInstance)) {
			// all in same project, build together
			return
				parse(Arrays.stream(compilationUnits).map(org.eclipse.jdt.internal.compiler.env.ICompilationUnit.class::cast).toArray(org.eclipse.jdt.internal.compiler.env.ICompilationUnit[]::new),
					apiLevel, compilerOptions, flags, compilationUnits[0].getJavaProject(), monitor)
				.entrySet().stream().collect(Collectors.toMap(entry -> (ICompilationUnit)entry.getKey(), entry -> entry.getValue()));
		}
		// build individually
		Map<ICompilationUnit, CompilationUnit> res = new HashMap<>(compilationUnits.length, 1.f);
		for (ICompilationUnit in : compilationUnits) {
			if (in instanceof org.eclipse.jdt.internal.compiler.env.ICompilationUnit compilerUnit) {
				res.put(in, parse(new org.eclipse.jdt.internal.compiler.env.ICompilationUnit[] { compilerUnit },
						apiLevel, compilerOptions, flags, in.getJavaProject(), monitor).get(compilerUnit));
			}
		}
		return res;
	}

	@Override
	public void parse(String[] sourceFilePaths, String[] encodings, FileASTRequestor requestor, int apiLevel,
			Map<String, String> compilerOptions, int flags, IProgressMonitor monitor) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'parse'");
	}

	@Override
	public void resolve(ICompilationUnit[] compilationUnits, String[] bindingKeys, ASTRequestor requestor, int apiLevel,
			Map<String, String> compilerOptions, IJavaProject project, WorkingCopyOwner workingCopyOwner, int flags,
			IProgressMonitor monitor) {
		var units = parse(compilationUnits, apiLevel, compilerOptions, flags, monitor);
		units.values().forEach(this::resolveBindings);
		if (requestor != null) {
			units.forEach(requestor::acceptAST);
			// TODO send request.acceptBinding according to input bindingKeys
		}
	}

	private void resolveBindings(CompilationUnit unit) {
		if (unit.getPackage() != null) {
			unit.getPackage().resolveBinding();
		} else if (!unit.types().isEmpty()) {
			((AbstractTypeDeclaration) unit.types().get(0)).resolveBinding();
		} else if (unit.getAST().apiLevel >= AST.JLS9 && unit.getModule() != null) {
			unit.getModule().resolveBinding();
		}
	}

	@Override
	public IBinding[] resolve(IJavaElement[] elements, int apiLevel, Map<String, String> compilerOptions,
			IJavaProject project, WorkingCopyOwner workingCopyOwner, int flags, IProgressMonitor monitor) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'resolve'");
	}

	@Override
	public CompilationUnit toCompilationUnit(org.eclipse.jdt.internal.compiler.env.ICompilationUnit sourceUnit,
			boolean initialNeedsToResolveBinding, IJavaProject project, List<Classpath> classpaths,
			NodeSearcher nodeSearcher, int apiLevel, Map<String, String> compilerOptions,
			WorkingCopyOwner workingCopyOwner, WorkingCopyOwner typeRootWorkingCopyOwner, int flags, IProgressMonitor monitor) {
		// TODO currently only parse
		CompilationUnit res = parse(new org.eclipse.jdt.internal.compiler.env.ICompilationUnit[] { sourceUnit},
				apiLevel, compilerOptions, flags, project, monitor).get(sourceUnit);
		if (initialNeedsToResolveBinding) {
			((JavacBindingResolver)res.ast.getBindingResolver()).isRecoveringBindings = (flags & ICompilationUnit.ENABLE_BINDINGS_RECOVERY) != 0;
			resolveBindings(res);
		}
		// For comparison
//		CompilationUnit res2  = CompilationUnitResolver.FACADE.toCompilationUnit(sourceUnit, initialNeedsToResolveBinding, project, classpaths, nodeSearcher, apiLevel, compilerOptions, typeRootWorkingCopyOwner, typeRootWorkingCopyOwner, flags, monitor);
//		//res.typeAndFlags=res2.typeAndFlags;
//		String res1a = res.toString();
//		String res2a = res2.toString();
//
//		AnnotationTypeDeclaration l1 = (AnnotationTypeDeclaration)res.types().get(0);
//		AnnotationTypeDeclaration l2 = (AnnotationTypeDeclaration)res2.types().get(0);
//		Object o1 = l1.bodyDeclarations().get(0);
//		Object o2 = l2.bodyDeclarations().get(0);
		return res;
	}

	private Map<org.eclipse.jdt.internal.compiler.env.ICompilationUnit, CompilationUnit> parse(org.eclipse.jdt.internal.compiler.env.ICompilationUnit[] sourceUnits, int apiLevel, Map<String, String> compilerOptions,
			int flags, IJavaProject javaProject, IProgressMonitor monitor) {
		if (sourceUnits.length == 0) {
			return Collections.emptyMap();
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		Context context = new Context();
		Map<org.eclipse.jdt.internal.compiler.env.ICompilationUnit, CompilationUnit> result = new HashMap<>(sourceUnits.length, 1.f);
		Map<JavaFileObject, CompilationUnit> filesToUnits = new HashMap<>();
		var problemConverter = new JavacProblemConverter(compilerOptions, context);
		DiagnosticListener<JavaFileObject> diagnosticListener = diagnostic -> {
			findTargetDOM(filesToUnits, diagnostic).ifPresent(dom -> {
				var newProblem = problemConverter.createJavacProblem(diagnostic);
				if (newProblem != null) {
					IProblem[] previous = dom.getProblems();
					IProblem[] newProblems = Arrays.copyOf(previous, previous.length + 1);
					newProblems[newProblems.length - 1] = newProblem;
					dom.setProblems(newProblems);
				}
			});
		};
		// must be 1st thing added to context
		context.put(DiagnosticListener.class, diagnosticListener);
		JavacUtils.configureJavacContext(context, compilerOptions, javaProject);
		var fileManager = (JavacFileManager)context.get(JavaFileManager.class);
		List<JavaFileObject> fileObjects = new ArrayList<>(); // we need an ordered list of them
		for (var sourceUnit : sourceUnits) {
			var unitFile = new File(new String(sourceUnit.getFileName()));
			Path sourceUnitPath;
			if (!unitFile.getName().endsWith(".java") || sourceUnit.getFileName() == null || sourceUnit.getFileName().length == 0) {
				sourceUnitPath = Path.of(new File("whatever.java").toURI());
			} else {
				sourceUnitPath = Path.of(unitFile.toURI());
			}
			var fileObject = fileManager.getJavaFileObject(sourceUnitPath);
			fileManager.cache(fileObject, CharBuffer.wrap(sourceUnit.getContents()));
			AST ast = createAST(compilerOptions, apiLevel, context);
			CompilationUnit res = ast.newCompilationUnit();
			result.put(sourceUnit, res);
			filesToUnits.put(fileObject, res);
			fileObjects.add(fileObject);
		}


		JCCompilationUnit javacCompilationUnit = null;
		JavacTask task = ((JavacTool)compiler).getTask(null, fileManager, null /* already added to context */, List.of() /* already set in options */, List.of() /* already set */, fileObjects, context);
		{
			// don't know yet a better way to ensure those necessary flags get configured
			var javac = com.sun.tools.javac.main.JavaCompiler.instance(context);
			javac.keepComments = true;
			javac.genEndPos = true;
			javac.lineDebugInfo = true;
		}

		try {
			var elements = task.parse().iterator();

			for (int i = 0 ; i < sourceUnits.length; i++) {
				if (elements.hasNext() && elements.next() instanceof JCCompilationUnit u) {
					javacCompilationUnit = u;
				} else {
					return Map.of();
				}
				String rawText = null;
				try {
					rawText = fileObjects.get(i).getCharContent(true).toString();
				} catch( IOException ioe) {
					// ignore
				}
				CompilationUnit res = result.get(sourceUnits[i]);
				AST ast = res.ast;
				int savedDefaultNodeFlag = ast.getDefaultNodeFlag();
				ast.setDefaultNodeFlag(ASTNode.ORIGINAL);
				JavacConverter converter = new JavacConverter(ast, javacCompilationUnit, context, rawText);
				converter.populateCompilationUnit(res, javacCompilationUnit);
				// javadoc problems explicitly set as they're not sent to DiagnosticListener (maybe find a flag to do it?)
				var javadocProblems = converter.javadocDiagnostics.stream()
						.map(problemConverter::createJavacProblem)
						.filter(Objects::nonNull)
						.toArray(IProblem[]::new);
				if (javadocProblems.length > 0) {
					int initialSize = res.getProblems().length;
					var newProblems = Arrays.copyOf(res.getProblems(), initialSize + javadocProblems.length);
					System.arraycopy(javadocProblems, 0, newProblems, initialSize, javadocProblems.length);
				    res.setProblems(newProblems);
			    }
				List<org.eclipse.jdt.core.dom.Comment> javadocComments = new ArrayList<>();
				res.accept(new ASTVisitor(true) {
					@Override
					public void postVisit(ASTNode node) { // fix some positions
						if( node.getParent() != null ) {
							if( node.getStartPosition() < node.getParent().getStartPosition()) {
								int parentEnd = node.getParent().getStartPosition() + node.getParent().getLength();
								if( node.getStartPosition() >= 0 ) {
									node.getParent().setSourceRange(node.getStartPosition(), parentEnd - node.getStartPosition());
								}
							}
						}
					}
					@Override
					public boolean visit(Javadoc javadoc) {
						javadocComments.add(javadoc);
						return true;
					}
				});
				addCommentsToUnit(javadocComments, res);
				attachNonDocComments(res, context, rawText, converter, compilerOptions);
				ast.setBindingResolver(new JavacBindingResolver(javaProject, task, context, converter));
				//
				ast.setOriginalModificationCount(ast.modificationCount()); // "un-dirty" AST so Rewrite can process it
				ast.setDefaultNodeFlag(savedDefaultNodeFlag);
			}
		} catch (IOException ex) {
			ILog.get().error(ex.getMessage(), ex);
		}

		return result;
	}

	private Optional<CompilationUnit> findTargetDOM(Map<JavaFileObject, CompilationUnit> filesToUnits, Object obj) {
		if (obj == null) {
			return Optional.empty();
		}
		if (obj instanceof JavaFileObject o) {
			return Optional.ofNullable(filesToUnits.get(o));
		}
		if (obj instanceof DiagnosticSource source) {
			return findTargetDOM(filesToUnits, source.getFile());
		}
		if (obj instanceof Diagnostic diag) {
			return findTargetDOM(filesToUnits, diag.getSource());
		}
		return Optional.empty();
	}

	private AST createAST(Map<String, String> options, int level, Context context) {
		AST ast = AST.newAST(level, JavaCore.ENABLED.equals(options.get(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES)));
		String sourceModeSetting = options.get(JavaCore.COMPILER_SOURCE);
		long sourceLevel = CompilerOptions.versionToJdkLevel(sourceModeSetting);
		if (sourceLevel == 0) {
			// unknown sourceModeSetting
			sourceLevel = ClassFileConstants.getLatestJDKLevel();
		ast.scanner.sourceLevel = sourceLevel;
		String compliance = options.get(JavaCore.COMPILER_COMPLIANCE);
		long complianceLevel = CompilerOptions.versionToJdkLevel(compliance);
		if (complianceLevel == 0) {
			// unknown sourceModeSetting
			complianceLevel = sourceLevel;
		}
		ast.scanner.complianceLevel = complianceLevel;
		ast.scanner.previewEnabled = JavaCore.ENABLED.equals(options.get(JavaCore.COMPILER_PB_ENABLE_PREVIEW_FEATURES));
		return ast;
	}

//
	/**
	 * Currently re-scans the doc to build the list of comments and then
	 * attach them to the already built AST.
	 * @param res
	 * @param context
	 * @param fileObject
	 * @param converter
	 * @param compilerOptions
	 */
	private void attachNonDocComments(CompilationUnit unit, Context context, String rawText, JavacConverter converter, Map<String, String> compilerOptions) {
		ScannerFactory scannerFactory = ScannerFactory.instance(context);
		List<Comment> nonJavadocComments = new ArrayList<>();
		JavadocTokenizer commentTokenizer = new JavadocTokenizer(scannerFactory, rawText.toCharArray(), rawText.length()) {
			@Override
			protected com.sun.tools.javac.parser.Tokens.Comment processComment(int pos, int endPos, CommentStyle style) {
				var res = super.processComment(pos, endPos, style);
				if (style != CommentStyle.JAVADOC || noCommentAt(pos)) { // javadoc comment already c and added
					var comment = converter.convert(res, null);
					comment.setSourceRange(pos, endPos - pos);
					nonJavadocComments.add(comment);
				}
				return res;
			}

			private boolean noCommentAt(int pos) {
				if (unit.getCommentList() == null) {
					return false;
				}
				return ((List<Comment>)unit.getCommentList()).stream()
						.noneMatch(other -> other.getStartPosition() <= pos && other.getStartPosition() + other.getLength() >= pos);
			}
		};
		Scanner javacScanner = new Scanner(scannerFactory, commentTokenizer) {
			// subclass just to access constructor
			// TODO DefaultCommentMapper.this.scanner.linePtr == -1?
		};
		do { // consume all tokens to populate comments
			javacScanner.nextToken();
		} while (javacScanner.token() != null && javacScanner.token().kind != TokenKind.EOF);
		org.eclipse.jdt.internal.compiler.parser.Scanner ecjScanner = new ASTConverter(compilerOptions, false, null).scanner;
		ecjScanner.recordLineSeparator = true;
		ecjScanner.skipComments = false;
		try {
			ecjScanner.setSource(rawText.toCharArray());
			do {
				ecjScanner.getNextToken();
			} while (!ecjScanner.atEnd());
		} catch (InvalidInputException ex) {
			JavaCore.getPlugin().getLog().log(org.eclipse.core.runtime.Status.error(ex.getMessage(), ex));
		}

		// need to scan with ecjScanner first to populate some line indexes used by the CommentMapper
		// on longer-term, implementing an alternative comment mapper based on javac scanner might be best
		addCommentsToUnit(nonJavadocComments, unit);
		unit.initCommentMapper(ecjScanner);
	}

	private static void addCommentsToUnit(Collection<Comment> comments, CompilationUnit res) {
		List<Comment> before = res.getCommentList() == null ? new ArrayList<>() : new ArrayList<>(res.getCommentList());
		before.addAll(comments);
		before.sort(Comparator.comparingInt(Comment::getStartPosition));
		res.setCommentTable(before.toArray(Comment[]::new));
	}

	private static class BindingBuilder extends ASTVisitor {
		public HashMap<String, IBinding> bindingMap = new HashMap<>();

		public BindingBuilder(HashMap<String, IBinding> bindingMap) {
			this.bindingMap = bindingMap;
		}

		@Override
		public boolean visit(TypeDeclaration node) {
			IBinding binding = node.resolveBinding();
			bindingMap.putIfAbsent(binding.getKey(), binding);
			return true;
		}

		@Override
		public boolean visit(MethodDeclaration node) {
			IBinding binding = node.resolveBinding();
			bindingMap.putIfAbsent(binding.getKey(), binding);
			return true;
		}

		@Override
		public boolean visit(EnumDeclaration node) {
			IBinding binding = node.resolveBinding();
			bindingMap.putIfAbsent(binding.getKey(), binding);
			return true;
		}

		@Override
		public boolean visit(RecordDeclaration node) {
			IBinding binding = node.resolveBinding();
			bindingMap.putIfAbsent(binding.getKey(), binding);
			return true;
		}

		@Override
		public boolean visit(SingleVariableDeclaration node) {
			IBinding binding = node.resolveBinding();
			bindingMap.putIfAbsent(binding.getKey(), binding);
			return true;
		}

		@Override
		public boolean visit(VariableDeclarationFragment node) {
			IBinding binding = node.resolveBinding();
			bindingMap.putIfAbsent(binding.getKey(), binding);
			return true;
		}

		@Override
		public boolean visit(AnnotationTypeDeclaration node) {
			IBinding binding = node.resolveBinding();
			bindingMap.putIfAbsent(binding.getKey(), binding);
			return true;
		}
	}

}
