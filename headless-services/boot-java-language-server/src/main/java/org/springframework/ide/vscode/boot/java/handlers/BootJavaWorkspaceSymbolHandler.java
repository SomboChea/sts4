/*******************************************************************************
 * Copyright (c) 2017 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.boot.java.handlers;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.springframework.ide.vscode.commons.java.IClasspath;
import org.springframework.ide.vscode.commons.java.IJavaProject;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.languageserver.util.WorkspaceSymbolHandler;
import org.springframework.ide.vscode.commons.util.text.LanguageId;
import org.springframework.ide.vscode.commons.util.text.TextDocument;

/**
 * @author Martin Lippert
 */
public class BootJavaWorkspaceSymbolHandler implements WorkspaceSymbolHandler {

	private SimpleLanguageServer server;
	private JavaProjectFinder projectFinder;
	private Map<String, SymbolProvider> symbolProviders;

	private List<SymbolInformation> symbols;

	public BootJavaWorkspaceSymbolHandler(SimpleLanguageServer server, JavaProjectFinder projectFinder, Map<String, SymbolProvider> specificProviders) {
		this.server = server;
		this.projectFinder = projectFinder;
		this.symbols = new ArrayList<>();
		this.symbolProviders = specificProviders;
	}

	@Override
	public List<? extends SymbolInformation> handle(WorkspaceSymbolParams params) {
		Path root = this.server.getWorkspaceRoot();

		symbols.clear();
		scanFiles(root.toFile());

		return collectSymbols();
	}

	private List<? extends SymbolInformation> collectSymbols() {
		return symbols;
	}

	private void scanFiles(File directory) {
		if (this.projectFinder.isProjectRoot(directory)) {
			IJavaProject project = this.projectFinder.find(directory);
			if (project != null) {
				scanProject(project, directory);
			}
		}
		else if (directory.isDirectory() && directory.exists()) {
			File[] files = directory.listFiles();
			for (File file : files) {
				scanFiles(file);
			}
		}
	}

	private void scanProject(IJavaProject project, File directory) {
		try {
			ASTParser parser = ASTParser.newParser(AST.JLS8);
			String[] classpathEntries = getClasspathEntries(project);
			scanFiles(parser, directory, classpathEntries);
		}
		catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void scanFiles(ASTParser parser, File directory, String[] classpathEntries) throws Exception {
		File[] javaFiles = directory.listFiles((file) -> file.getName().endsWith(".java"));
		for (File javaFile : javaFiles) {
			if (javaFile.isFile() && javaFile.exists()) {
				scanFile(parser, javaFile, classpathEntries);
			}
		}

		File[] directories = directory.listFiles((file) -> file.isDirectory() && file.exists());
		for (File dir : directories) {
			scanFiles(parser, dir, classpathEntries);
		}
	}

	private void scanFile(ASTParser parser, File javaFile, String[] classpathEntries) throws Exception {
		Path path = javaFile.toPath();

		String unitName = "/" + path.getFileName().toString();
		parser.setUnitName(unitName);

		String content = new String(Files.readAllBytes(path));
		parser.setSource(content.toCharArray());

		Map<String, String> options = JavaCore.getOptions();
		JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
		parser.setCompilerOptions(options);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setStatementsRecovery(true);
		parser.setBindingsRecovery(true);
		parser.setResolveBindings(true);

		String[] sourceEntries = new String[] {};
		parser.setEnvironment(classpathEntries, sourceEntries, null, true);

		CompilationUnit cu = (CompilationUnit) parser.createAST(null);
		if (cu != null) {
			System.out.println("AST node found: " + cu.getClass().getName());

			String docURI = "file://" + javaFile.getAbsolutePath();
			TextDocument doc = new TextDocument(docURI, LanguageId.PLAINTEXT, 0, content);

			scanAST(cu, doc);
		}
	}

	private void scanAST(final CompilationUnit cu, final TextDocument doc) {
		cu.accept(new ASTVisitor() {

			@Override
			public boolean visit(SingleMemberAnnotation node) {
				try {
					extractSymbolInformation(node, doc);
				}
				catch (Exception e) {
					e.printStackTrace();
				}

				return super.visit(node);
			}

			@Override
			public boolean visit(NormalAnnotation node) {
				try {
					extractSymbolInformation(node, doc);
				}
				catch (Exception e) {
					e.printStackTrace();
				}

				return super.visit(node);
			}

			@Override
			public boolean visit(MarkerAnnotation node) {
				try {
					extractSymbolInformation(node, doc);
				}
				catch (Exception e) {
					e.printStackTrace();
				}

				return super.visit(node);
			}
		});
	}

	private void extractSymbolInformation(Annotation node, TextDocument doc) {
		System.out.println("annotation found: " + node.toString());
		ITypeBinding typeBinding = node.resolveTypeBinding();

		if (typeBinding != null) {
			String qualifiedTypeName = typeBinding.getQualifiedName();

			SymbolProvider provider = symbolProviders.get(qualifiedTypeName);
			if (provider != null) {
				SymbolInformation symbol = provider.getSymbol(node, doc);
				if (symbol != null) {
					symbols.add(symbol);
				}
			}
			else {
				SymbolInformation symbol = provideDefaultSymbol(node, doc);
				if (symbol != null) {
					symbols.add(symbol);
				}
			}
		}
	}

	private SymbolInformation provideDefaultSymbol(Annotation node, TextDocument doc) {
		try {
			ITypeBinding type = node.resolveTypeBinding();
			if (type != null) {
				String qualifiedName = type.getQualifiedName();
				if (qualifiedName != null && qualifiedName.startsWith("org.springframework")) {
					SymbolInformation symbol = new SymbolInformation(node.toString(), SymbolKind.Interface,
							new Location(doc.getUri(), doc.toRange(node.getStartPosition(), node.getLength())));
					return symbol;
				}
			}
		}
		catch (Exception e) {
			e.printStackTrace();
		}

		return null;
	}

	private String[] getClasspathEntries(IJavaProject project) throws Exception {
		IClasspath classpath = project.getClasspath();
		Stream<Path> classpathEntries = classpath.getClasspathEntries();
		return classpathEntries
				.filter(path -> path.toFile().exists())
				.map(path -> path.toAbsolutePath().toString()).toArray(String[]::new);
	}

}
