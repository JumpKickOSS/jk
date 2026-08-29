def src = projectDir.resolve('src')
long n = 0
src.toFile().eachFileRecurse { f ->
  if (f.file && f.name.endsWith('.java')) n += f.readLines().size()
}
outDir.resolve('line-count.txt').toFile().parentFile.mkdirs()
outDir.resolve('line-count.txt').toFile().text = String.valueOf(n)
