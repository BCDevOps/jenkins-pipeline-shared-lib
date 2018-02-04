# jenkins-pipeline-shared-lib
Extending Jenkins with a Shared Library


https://jenkins.io/doc/book/pipeline/shared-libraries/


Directory STructure:

```
(root)
+- src                     # Groovy source files
|   +- org
|       +- foo
|           +- Bar.groovy  # for org.foo.Bar class
+- vars
|   +- foo.groovy          # for global 'foo' variable
|   +- foo.txt             # help for 'foo' variable
```

The src directory looks like a standard Java source directory structure. This directory is added to the classpath when executing Pipelines.

The vars directory hosts scripts that define global variables accessible from Pipeline. The basename of each *.groovy file should be a Groovy (~ Java) identifier, conventionally camelCased. The matching *.txt, if present, can contain documentation, processed through the system’s configured markup formatter (so may really be HTML, Markdown, etc., though the txt extension is required).

The Groovy source files in these directories get the same “CPS transformation” as in Scripted Pipeline.


Pipeline Steps 
https://jenkins.io/doc/pipeline/steps/
