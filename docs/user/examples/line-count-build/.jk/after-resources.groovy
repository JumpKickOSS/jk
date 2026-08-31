// Counts lines of Java under src/ and writes the total to line-count.txt.
//
// outDir is created empty before this script runs and is the only place a stem script
// may write. jk merges it into the module's resources, so demo.App reads the file back
// off the classpath as /line-count.txt.
import groovy.io.FileType

long total = 0
projectDir.resolve('src').toFile().eachFileRecurse(FileType.FILES) { file ->
    if (file.name.endsWith('.java')) file.eachLine { total++ }
}
outDir.resolve('line-count.txt').toFile().text = "$total\n"
