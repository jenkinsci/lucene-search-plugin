#!/usr/bin/groovy
@Grab('org.eclipse.text:org.eclipse.text:3.5.101')
@Grab('org.eclipse.tycho:org.eclipse.jdt.core:3.10.0.v20140604-1726')
@Grab('org.eclipse.birt.runtime:org.eclipse.core.resources:3.9.0.v20140514-1307')


import groovy.io.FileType
import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.formatter.CodeFormatter
import org.eclipse.jface.text.Document

def settings =  new XmlSlurper().parse('codestyle.xml')
def options = settings[0].children[0].children.collectEntries { [it.attributes.id, it.attributes.value] }
def codeFormatter = ToolFactory.createCodeFormatter(options);

new File("src").eachFileRecurse(FileType.FILES) {
	if (it.name =~ /^[^.]*\.java$/) {
		def source = it.getText("utf-8")
		def edit = codeFormatter.format(CodeFormatter.K_COMPILATION_UNIT, source, 0, source.length(), 0, '\n')
		def document = new Document(source);
		edit.apply(document)
		def formattedSource = document.get()
		if (formattedSource != source) {
			new File(it.toString()).withWriter { it.write(document.get()) }
			System.err.println("Changing formatting of ${it}")
			//System.err.println("${it} is incorrectly formatted")
			////System.exit(1)
		}
	}
}
