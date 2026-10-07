using System.Reflection;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;

internal static class Program
{

static int Main(string[] args)
{
    if(args.Length == 0 || args[0] is "--help" or "-h")
    {
        Console.WriteLine("Noir C# compiler host");
        Console.WriteLine("  validate <file.cs> [file.cs ...]");
        Console.WriteLine("  compile <output.dll> <file.cs> [file.cs ...]");
        Console.WriteLine("  build <projectDir> <output.dll>");
        return 0;
    }

    var mode=args[0].ToLowerInvariant();
    if(mode!="validate" && mode!="compile" && mode!="build")
    {
        Console.Error.WriteLine("Unknown command: "+mode);
        return 2;
    }

    string? output;
    List<string> sourceFiles;
    if(mode=="build")
    {
        if(args.Length<3){Console.Error.WriteLine("Usage: build <projectDir> <output.dll>");return 2;}
        var projectDir=Path.GetFullPath(args[1]);
        output=Path.GetFullPath(args[2]);
        if(!Directory.Exists(projectDir)){Console.Error.WriteLine("Project directory not found: "+projectDir);return 2;}
        sourceFiles=Directory.EnumerateFiles(projectDir,"*.cs",SearchOption.AllDirectories)
            .Where(p=>!p.Contains(Path.DirectorySeparatorChar+"bin"+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)
                   &&!p.Contains(Path.DirectorySeparatorChar+"obj"+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))
            .OrderBy(p=>p,StringComparer.OrdinalIgnoreCase).ToList();
    }
    else
    {
        output=mode=="compile" ? args.ElementAtOrDefault(1) : null;
        var start=mode=="compile" ? 2 : 1;
        if(args.Length<=start){Console.Error.WriteLine("No C# source files supplied.");return 2;}
        sourceFiles=args.Skip(start).ToList();
    }

    if(sourceFiles.Count==0){Console.Error.WriteLine("No C# source files found.");return 2;}
    var sources=sourceFiles.Select(File.ReadAllText).ToArray();
    var trees=sources.Select((s,i)=>CSharpSyntaxTree.ParseText(
        s,
        CSharpParseOptions.Default.WithLanguageVersion(LanguageVersion.CSharp14),
        path:Path.GetFullPath(sourceFiles[i]))).ToArray();

    var refs=new List<MetadataReference>();
    foreach(var asm in AppDomain.CurrentDomain.GetAssemblies())
    {
        if(!asm.IsDynamic && !string.IsNullOrWhiteSpace(asm.Location))
            refs.Add(MetadataReference.CreateFromFile(asm.Location));
    }

    var noir=typeof(Noir.Engine).Assembly;
    if(!string.IsNullOrWhiteSpace(noir.Location))
        refs.Add(MetadataReference.CreateFromFile(noir.Location));

    var compilation=CSharpCompilation.Create(
        assemblyName:output==null?"NoirScriptValidation":Path.GetFileNameWithoutExtension(output),
        syntaxTrees:trees,
        references:refs.DistinctBy(r=>r.Display,StringComparer.OrdinalIgnoreCase),
        options:new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary,
            optimizationLevel:OptimizationLevel.Release,
            nullableContextOptions:NullableContextOptions.Enable));

    var errors=compilation.GetDiagnostics()
        .Where(d=>d.Severity is DiagnosticSeverity.Error or DiagnosticSeverity.Warning)
        .ToArray();

    foreach(var d in errors)
        Console.WriteLine($"{d.Severity}: {d.Id}: {d.GetMessage()}");

    if(errors.Any(d=>d.Severity==DiagnosticSeverity.Error)) return 1;

    if(mode=="compile" || mode=="build")
    {
        if(string.IsNullOrWhiteSpace(output))
        {
            Console.Error.WriteLine("No output DLL was supplied.");
            return 2;
        }

        var outputPath=Path.GetFullPath(output);
        var parent=Path.GetDirectoryName(outputPath);
        if(!string.IsNullOrWhiteSpace(parent)) Directory.CreateDirectory(parent);

        using var stream=File.Create(outputPath);
        var emit=compilation.Emit(stream);
        if(!emit.Success)
        {
            foreach(var d in emit.Diagnostics.Where(d=>d.Severity is DiagnosticSeverity.Error or DiagnosticSeverity.Warning))
                Console.WriteLine($"{d.Severity}: {d.Id}: {d.GetMessage()}");
            return 1;
        }

        Console.WriteLine("Emitted "+outputPath);
    }
    return 0;
}

}
