package ksp.kos.ideaplugin.dataflow;

import com.intellij.psi.PsiFile;
import ksp.kos.ideaplugin.KerboScriptFile;
import ksp.kos.ideaplugin.expressions.*;
import ksp.kos.ideaplugin.psi.*;
import ksp.kos.ideaplugin.reference.Reference;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created on 12/03/16.
 *
 * @author ptasha
 */
public class FunctionFlow extends BaseFlow<FunctionFlow> implements NamedFlow<FunctionFlow> {
    private final KerboScriptFile file;
    private final String name;

    private final Context parameters;
    private final Context instructions;

    public FunctionFlow(KerboScriptFile file, String name, Context parameters, Context instructions) {
        this.file = file;
        this.name = name;
        this.parameters = parameters;
        this.instructions = instructions;
    }

    public static FunctionFlow parse(KerboScriptDeclareFunctionClause function) throws SyntaxException {
        FunctionParser parser = new FunctionParser();
        String name = function.getName();
        parser.parseInstructions(function.getInstructionBlock().getInstructionList());
        return new FunctionFlow(function.getKerboScriptFile(), name, parser.getContext(), parser.getFlowContext());
        // throw new SyntaxException("Return statement is not found for function " + name); // TODO check for return statement
    }

    @Override
    public FunctionFlow differentiate() {
        Context parameters = new Context();
        this.parameters.differentiate(parameters); // TODO single parameter
        Context flows = new Context(parameters);
        this.instructions.differentiate(flows);
        parameters.buildMap();
        flows.buildMap();
        flows.getReturnFlow().addDependee(this);
        flows.simplify();
        parameters.simplify(); // TODO teach Function to deal with it

        return new FunctionFlow(file, name + "_", parameters, flows);
    }

    @Override
    public String getText() {
        String text = "";
        text += "function " + name + " {\n";
        text += parameters.getText() + "\n";
        if (!parameters.getList().isEmpty()) text += "\n";
        text += instructions.getText() + "\n";
        text += "}";
        return text;
    }

    @Override
    public String getName() {
        return name;
    }

    public Set<ImportFlow> getImports(KerboScriptFile newFile) {
        HashSet<ImportFlow> imports = new HashSet<>();
        HashSet<String> context = new HashSet<>();
        visitExpresssions(new ExpressionVisitor() {
            @Override
            public void visitFunction(Function function) {
                Reference ref = Reference.function(file, function.getName());
                addImport(findFunction(ref));
                super.visitFunction(function);
            }

            @Override
            public void visitVariable(Variable variable) {
                String name = variable.getName();
                if (!context.contains(name)) {
                    addImport(file.findVariable(name));
                }
                context.add(name);
                super.visitVariable(variable);
            }

            private void addImport(KerboScriptNamedElement resolved) {
                if (resolved != null && resolved.isReal()) {
                    KerboScriptFile dependency = resolved.getKerboScriptFile();
                    if (dependency != newFile) {
                        imports.add(new ImportFlow(dependency));
                    }
                }
            }
        });
        return imports;
    }

    public void visitExpresssions(ExpressionVisitor visitor) {
        parameters.visit(visitor);
        instructions.visit(visitor);
    }

    private KerboScriptNamedElement findFunction(Reference ref) {
        KerboScriptNamedElement resolved = ref.findDeclaration();
        if (resolved!=null) {
            return resolved;
        }
        String name = ref.getName();
        if (name.endsWith("_")) {
            while (name.endsWith("_")) {
                name = name.substring(0, name.length() - 1);
            }
            KerboScriptNamedElement orig = Reference.function(ref.getKingdom(), name).findDeclaration();
            if (orig != null) {
                PsiFile psiFile = orig.getContainingFile();
                if (psiFile instanceof KerboScriptFile) {
                    String fileName = ((KerboScriptFile) psiFile).getPureName();
                    if (!fileName.endsWith("_")) {
                        KerboScriptFile diffFile = this.file.findFile(fileName + "_");
                        if (diffFile!=null) {
                            return Reference.function(diffFile, ref.getName()).findDeclaration();
                        }
                    }
                }
            }
        }
        return null;
    }

    public Reference getNextToDiff(Map<Reference, FunctionFlow> context) {
        AtomicReference<Reference> reference = new AtomicReference<>();
        visitExpresssions(new ExpressionVisitor() {
            @Override
            public void visitFunction(Function function) {
                if (reference.get() == null) {
                    String name = function.getName();
                    Reference ref = Reference.function(file, name);
                    KerboScriptNamedElement declaration = findFunction(ref);
                    if (declaration == null || !declaration.isReal()) {
                        reference.set(undiff(ref));
                    } else {
                        super.visitFunction(function);
                    }
                }
            }

            @Nullable
            private Reference undiff(Reference reference) {
                String name = reference.getName();
                if (name.endsWith("_")) {
                    name = name.substring(0, name.length() - 1);
                    Reference undiff = Reference.function(reference.getKingdom(), name);
                    if (!context.containsKey(undiff)) {
                        KerboScriptNamedElement resolved = findFunction(undiff);
                        if (resolved != null && resolved.isReal()) {
                            return resolved;
                        } else {
                            return undiff(undiff);
                        }
                    } else {
                        return null;
                    }
                }
                return null;
            }

            @Override
            public void visit(Expression expression) {
                if (reference.get() == null) {
                    super.visit(expression);
                }
            }
        });
        return reference.get();
    }

    private static class FunctionParser extends FlowParser {
        private final FlowParser parser = new FlowParser(getContext());
        private boolean parametersParsed = false;

        @Override
        public boolean parseInstruction(KerboScriptInstruction instruction) throws SyntaxException {
            if (parametersParsed) {
                return parser.parseInstruction(instruction);
            } else if (instruction instanceof KerboScriptDeclareStmt) {
                KerboScriptDeclareStmt declareStmt = (KerboScriptDeclareStmt) instruction;
                KerboScriptDeclareParameterClause declareParameterClause = declareStmt.getDeclareParameterClause();
                if (declareParameterClause != null) {
                    addFlow(ParameterFlow.parse(declareParameterClause));
                } else if (declareStmt.getDeclareIdentifierClause() != null) {
                    parametersParsed = true;
                    return parser.parseInstruction(instruction);
                }
            } else {
                parametersParsed = true;
                return parser.parseInstruction(instruction);
            }
            return true;
        }

        public Context getFlowContext() {
            return parser.getContext();
        }
    }
}
