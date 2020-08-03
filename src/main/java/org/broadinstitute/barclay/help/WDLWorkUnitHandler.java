package org.broadinstitute.barclay.help;

import org.apache.commons.lang3.tuple.Pair;

import org.broadinstitute.barclay.argparser.*;
import org.broadinstitute.barclay.argparser.RuntimeProperties;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The WDL work unit handler. Its main task is to convert the types for all arguments for a given work
 * unit (tool) from Java types to WDL-compatible types by updating the freemarker map with the transformed types.
 */
public class WDLWorkUnitHandler extends DefaultDocWorkUnitHandler {
    private static final String GATK_FREEMARKER_TEMPLATE_NAME = "wdlToolTemplate.wdl.ftl";

    private static final String LONG_OPTION_PREFIX = "--";

    /**
     * Name of the top level freemarker map entry for runtime properties.
     *
     * Note that the property names used in this map will appear as workflow and task argument names in the
     * generated WDL, and should therefore not collide with any WDL reserved words.
     */
    public static final String RUNTIME_PROPERTIES = "runtimeProperties";
    /**
     * runtime memory property (stored in "runtimeProperties", used to initialize arg value in JSON)
     */
    public static final String RUNTIME_PROPERTY_MEMORY = "memoryRequirements";
    /**
     * runtime disks property (stored in "runtimeProperties", used to initialize arg value in JSON)
     */
    public static final String RUNTIME_PROPERTY_DISKS = "diskRequirements";
    /**
     * cpu property
     */
    public static final String RUNTIME_PROPERTY_CPU = "cpuRequirements";
    /**
     * bootDiskSizeGb property
     */
    public static final String RUNTIME_PROPERTY_BOOT_DISK_SIZE_GB = "bootdisksizegbRequirements";

    /**
     * preemptible property
     */
    public static final String RUNTIME_PROPERTY_PREEMPTIBLE = "preemptibleRequirements";

    /**
     * name of the top level freemarker map entry for runtime outputs
     */
    public static final String RUNTIME_OUTPUTS = "runtimeOutputs";

    /**
     * name of the top level freemarker map entry for required runtime outputs
     */
    public static final String REQUIRED_OUTPUTS = "requiredOutputs";

    /**
     * name of the top level freemarker map entry for companion resources
     */
    public static final String COMPANION_RESOURCES = "companionResources";

    /**
     * the name used in the freemarker template as an argument placeholder for positional args; this constant
     * must be kept in sync with the corresponding one used in the template
     */
    public static final String POSITIONAL_ARGS = "positionalArgs";

    // keep track of tool outputs (Map<argName, argType>)
    private Map<String, String> runtimeOutputs = new LinkedHashMap<>();

    // requiredOutputs is a subset of runtimeOutputs; the data is somewhat redundant with runtimeOutputs but
    // simplifies access from within the template
    private Map<String, String> requiredOutputs = new LinkedHashMap<>();

    // keep track of companion files (Map<argName, List<Map<companionName, attributes>>>) for
    // arguments for this work unit
    final Map<String, List<Map<String, Object>>> companionFiles = new HashMap<>();

    /**
     * Create the WDL work unit handler for a single work unit.
     * @param doclet the controlling doclet for this work unit
     */
    public WDLWorkUnitHandler(final HelpDoclet doclet) {
        super(doclet);
    }

    /**
     * @param workUnit the DocWorkUnit object being processed
     * @return the name of a the freemarker template to be used for the class being documented.
     * Must reside in the folder passed to the Barclay Doclet via the "-settings-dir" parameter to
     * Javadoc.
     */
    @Override
    public String getTemplateName(final DocWorkUnit workUnit) { return GATK_FREEMARKER_TEMPLATE_NAME; }

    @Override
    protected void addCommandLineArgumentBindings(final DocWorkUnit currentWorkUnit, final CommandLineArgumentParser clp) {
        super.addCommandLineArgumentBindings(currentWorkUnit, clp);

        // add the properties required by the WDL template for workflow outputs, required outputs and companions
        currentWorkUnit.getRootMap().put(RUNTIME_OUTPUTS, runtimeOutputs);
        currentWorkUnit.getRootMap().put(REQUIRED_OUTPUTS, requiredOutputs);
        currentWorkUnit.getRootMap().put(COMPANION_RESOURCES, companionFiles);
    }

    /**
     * Add the named argument {@code argDed}to the property map if applicable.
     * @param currentWorkUnit current work unit
     * @param args the freemarker arg map
     * @param argDef the arg to add
     */
    protected void processNamedArgument(
            final DocWorkUnit currentWorkUnit,
            final Map<String, List<Map<String, Object>>> args,
            final NamedArgumentDefinition argDef)
    {
        // suppress special args such as --help and --version from showing up in the WDL
        if (!argDef.getUnderlyingField().getDeclaringClass().equals(SpecialArgumentsCollection.class)) {
            super.processNamedArgument(currentWorkUnit, args, argDef);
        }
    }

    @Override
    protected String processNamedArgument(
            final Map<String, Object> argBindings,
            final NamedArgumentDefinition argDef,
            final String fieldCommentText) {
        final String argCategory = super.processNamedArgument(argBindings, argDef, fieldCommentText);

        // Store the actual (unmodified) arg name that the app will recognize, for use in the task command block.
        // Now generate a WDL-friendly name if necessary (if "input" and "output" are reserved words in WDL and
        // can't be used for arg names; also WDL doesn't accept embedded "-" for variable names, so use a non-kebab
        // name with an underscore) for use as the argument name in the rest of the WDL source.
        final String actualArgName = (String) argBindings.get("name");
        argBindings.put("actualArgName", actualArgName);
        String wdlName = LONG_OPTION_PREFIX + transformJavaNameToWDLName(actualArgName.substring(2));
        argBindings.put("name", wdlName);

        propagateArgument(wdlName, argDef, argBindings);

        return argCategory;
    }

    @Override
    protected void processPositionalArguments(
            final CommandLineArgumentParser clp,
            final Map<String, List<Map<String, Object>>> argBindings) {
        super.processPositionalArguments(clp, argBindings);
        final PositionalArgumentDefinition argDef = clp.getPositionalArgumentDefinition();
        if (argDef != null) {
            final Map<String, Object> positionalArgBindings = argBindings.get("positional").get(0);
            propagateArgument(POSITIONAL_ARGS, argDef, positionalArgBindings);
        }
    }

    @SuppressWarnings("unchecked")
    protected void propagateArgument(
            final String wdlArgName,
            final ArgumentDefinition argDef,
            final Map<String, Object> argBindings) {
        // positional
        final String preProcessedType = (String) argBindings.get("type");
        final WorkflowResource workflowResource = getWorkflowResource(argDef);

        // replace the java type of the argument with the appropriate wdl type, and set the WDL input type
        final String wdlType = getWDLTypeForArgument(argDef, null, preProcessedType);
        final String wdlInputType = getWDLTypeForArgument(argDef, workflowResource, preProcessedType);

        argBindings.put("type", wdlType);
        argBindings.put("wdlinputtype", wdlInputType);
        argBindings.put("defaultValue", defaultValueAsJSON(wdlType, (String) argBindings.get("defaultValue")));

        // finally, keep track of the outputs and companions
        final boolean argIsRequired = wdlArgName.equals(POSITIONAL_ARGS) || ((argBindings.get("required")).equals("yes"));
        if (workflowResource != null) {
            propagateWorkflowAttributes(workflowResource, wdlArgName, wdlType, !argIsRequired);
        }
    }

    /**
     * Retrieve and validate the {@link WorkflowResource} for an {@link ArgumentDefinition}.
     * @param argDef {@link ArgumentDefinition} from which to retrieve the {@link WorkflowResource}
     * @return the {@link WorkflowResource} or null if no {@link WorkflowResource} is present on this arg field
     */
    final protected WorkflowResource getWorkflowResource(final ArgumentDefinition argDef) {
        final WorkflowResource workFlowResource = argDef.getUnderlyingField().getAnnotation(WorkflowResource.class);
        if (workFlowResource != null && workFlowResource.input() == false && workFlowResource.output() == false) {
            throw new IllegalArgumentException(String.format(
                    "WorkFlowResource for %s in %s must be marked as either an INPUT or an OUTPUT",
                    argDef.getUnderlyingField(),
                    argDef.getContainingObject().getClass()
            ));
        }
        return workFlowResource;
    }

    /**
     * Update the list of workflow output resources, and update companion files.
     *
     * @param workflowResource the {@link WorkflowResource} to use when updating runtime outputs, may not be null
     * @param wdlName the wdlname for this workflow resource
     * @param wdlType the wdltype for this workflow resource
     */
    protected void propagateWorkflowAttributes(
            final WorkflowResource workflowResource,
            final String wdlName,
            final String wdlType,
            final boolean resourceIsOptional) {
        // add the source argument to the list of workflow outputs
        if (workflowResource.output()) {
            runtimeOutputs.put(wdlName, wdlType);
            if (!resourceIsOptional) {
                requiredOutputs.put(wdlName, wdlType);
            }
        }

        final List<Map<String, Object>> argCompanions = new ArrayList<>();
        for (final String companion : workflowResource.companionResources()) {
            final String companionArgOption = LONG_OPTION_PREFIX + companion;

            final Map<String, Object> companionMap = new HashMap<>();
            companionMap.put("name", companionArgOption);
            companionMap.put("summary",
                    String.format(
                            "Companion resource for %s",
                            wdlName.equals(POSITIONAL_ARGS) ?
                                    POSITIONAL_ARGS :
                                    wdlName.substring(2)));
            argCompanions.add(companionMap);
            if (workflowResource.output()) {
                runtimeOutputs.put(companionArgOption, wdlType);
                if (!resourceIsOptional) {
                    requiredOutputs.put(companionArgOption, wdlType);
                }
            }
        }
        companionFiles.put(wdlName, argCompanions);
    }

    /**
     * Return a String that represents the WDL type for this arg, which is a variant of the  user-friendly doc
     * type chosen by the doc system. Interrogates the structured NamedArgumentDefinition type to transform and
     * determine the resulting WDL type.
     *
     * @param argDef the Barclay NamedArgumentDefinition for this arg
     * @param workflowResource the WorkflowResource for this argDef, may be null
     * @param argDocType the display type as chosen by the Barclay doc system for this arg. this is what
     * @return the WDL type to be used for this argument
     */
    protected String getWDLTypeForArgument(
            final ArgumentDefinition argDef,
            final WorkflowResource workflowResource,
            final String argDocType
    ) {
        final Field argField = argDef.getUnderlyingField();
        final Class<?> argumentClass = argField.getType();

        // start the data type chosen by the doc system and transform that based on the underlying
        // java class/type
        String wdlType = argDocType;

        // if the underlying field is a collection type; it needs to map to "Array", and then the
        // type param has to be converted to a WDL type
        if (argDef.isCollection()) {
            Pair<String, String> conversionPair = transformToWDLCollectionType(argumentClass);
            if (conversionPair != null) {
                wdlType = wdlType.replace(conversionPair.getLeft(), conversionPair.getRight());
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "Unrecognized collection type %s for argument %s in work unit %s." +
                                        "Argument collection type must be one of List or Set.",
                                argumentClass,
                                argField.getName(),
                                argField.getDeclaringClass()));
            }

            // Convert any Collection type params; this only handles a single generic type parameter (i.e List<T>),
            // where type T can in turn be a generic type, again with a single type param. This is sufficient to
            // accommodate all existing cases, with the most complex being List<FeatureInput<T>>. If this code
            // encounters anything more complex, it will throw. Supporting more deeply nested generic types will
            // require additional code.
            final Type typeParamType = argField.getGenericType();
            if (typeParamType instanceof ParameterizedType) {
                final ParameterizedType pType = (ParameterizedType) typeParamType;
                final Type genericTypes[] = pType.getActualTypeArguments();
                if (genericTypes.length != 1) {
                    throw new RuntimeException(String.format(
                            "Generating WDL for tools with arguments that have types that require multiple type parameters is not supported " +
                                    "(class %s for arg %s in %s has multiple type parameters).",
                            argumentClass,
                            argField.getName(),
                            argField.getDeclaringClass()));
                }
                ParameterizedType pType2 = null;
                Type genericTypes2[];
                Class<?> nestedTypeClass;
                try {
                    // we could have nested generic types, like "List<FeatureInput<VariantContext>>", which needs
                    // to translate to "List<File>"
                    if (genericTypes[0] instanceof ParameterizedType) {
                        pType2 = (ParameterizedType) genericTypes[0];
                        genericTypes2 = pType2.getActualTypeArguments();
                        if (genericTypes2.length != 1) {
                            throw new RuntimeException(String.format(
                                    "Generating WDL for tools with args with multiple type parameters is not supported " +
                                            "(class %s for arg %s in %s has multiple type parameters).",
                                    argumentClass,
                                    argField.getName(),
                                    argField.getDeclaringClass()));
                        }

                        nestedTypeClass = Class.forName(pType2.getRawType().getTypeName());
                        wdlType = convertJavaTypeToWDLType(workflowResource, nestedTypeClass, wdlType, argField.getDeclaringClass().toString());
                    } else {
                        nestedTypeClass = Class.forName(genericTypes[0].getTypeName());
                        wdlType = convertJavaTypeToWDLType(workflowResource, nestedTypeClass, wdlType, argField.getDeclaringClass().toString());
                    }
                    return wdlType;
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(String.format(
                            "WDL generation can't find class %s for %s",
                            pType2.getRawType().toString(),
                            argField.getDeclaringClass()), e);
                }
            } else {
                throw new RuntimeException(String.format(
                        "Generic type must have a ParameterizedType (class %s for argument %s/%s)",
                        argumentClass,
                        argField.getName(),
                        argField.getDeclaringClass()));
            }
        }

        return convertJavaTypeToWDLType(workflowResource, argumentClass, wdlType, argField.getDeclaringClass().toString());
    }

    /**
     * Given a Java class representing the underlying field  type of an argument, and a human readable doc type,
     * convert the docType to a WDL type.
     *
     * @param workflowResource the WorkflowResource associated with the instance of argumentClass, may be null
     * @param argumentClass the Class for the underlying field of the argument being converted
     * @param docType a string representing the human readable type assigned by the Barclay doc system
     * @param sourceContext a String describing the context for this argument, used for error reporting
     * @return the docType string transformed to the corresponding WDL type
     */
    protected String convertJavaTypeToWDLType(
            final WorkflowResource workflowResource,
            final Class<?> argumentClass,
            final String docType, final String sourceContext) {
        final String convertedWDLType;
        final Pair<String, String> typeConversionPair = transformToWDLType(argumentClass);

        // If this type is for an arg that is a WorkflowResource that is a workflow output, and its type
        // is file, we need to use String as the input type for this arg to prevent the workflow
        // manager from attempting to localize the (non-existent) output file when localizing inputs
        if (typeConversionPair != null) {
            convertedWDLType = docType.replace(
                    typeConversionPair.getKey(),
                    transformWorkflowResourceOutputTypeToInputType(workflowResource, typeConversionPair.getValue()));
        } else if (argumentClass.isEnum()) {
             convertedWDLType = docType.replace(
                     argumentClass.getSimpleName(),
                     "String");
        } else {
            throw new RuntimeException(
                    String.format(
                            "Don't know how to convert Java type %s in %s to a corresponding WDL type. " +
                                    "The WDL generator type converter code must be updated to support this Java type.",
                            argumentClass,
                            sourceContext));
        }
        return convertedWDLType;
    }

    /**
     * If this type is for an arg that is a WorkflowResource that is a workflow output, and its type is file,
     * we need to use a different type (String) as the input type for this arg to prevent the workflow manager
     * from attempting to localize the (non-existent) output file when localizing inputs. Transform
     *
     * @param workflowResource WorkflowResource for this type instance, if any (may be null)
     * @param convertedWDLType the wdl type for this type instance
     * @return
     */
    protected String transformWorkflowResourceOutputTypeToInputType(
            final WorkflowResource workflowResource,
            final String convertedWDLType) {
        return workflowResource != null && workflowResource.output() && convertedWDLType.equals("File") ?
                "String" :
                convertedWDLType;
    }

    /**
     * Given an argument class, return a String pair representing the string that should be replaced (the Java type),
     * and the string to substitute (the corresponding WDL type), i.e., for an argument with type Java Integer.class,
     * return the Pair ("Integer", "Int") to convert from the Java type to the corresponding WDL type.
     *
     * @param argumentClass Class of the argument being converter
     * @return a String pair representing the original and replacement type text, or null if no conversion is available
     */
    protected Pair<String, String> transformToWDLType(final Class<?> argumentClass) {
        return WDLTransforms.transformToWDLType(argumentClass);
    }

    /**
     * Return the default value suitably formatted as a JSON value. This primarily involves quoting strings and enum
     * values, including arrays thereof.
     *
     * @param wdlType
     * @param defaultWDLValue
     * @return
     */
    protected String defaultValueAsJSON(
            final String wdlType,
            final String defaultWDLValue) {

        if (defaultWDLValue.equals("null") || defaultWDLValue.equals("\"\"") || defaultWDLValue.equals("[]")) {
            return defaultWDLValue;
        } else if (defaultWDLValue.startsWith("[") && wdlType.equals("Array[String]")) {
            // the array is already populated with a value (since we didn't execute the "[]" branch above),
            // so quote the individual values
            return quoteWDLArrayValues(defaultWDLValue);
        } else if (wdlType.equals("String")) {
            return "\"" + defaultWDLValue + "\"";
        } else if (wdlType.equals("Float")) {
            if (defaultWDLValue.equalsIgnoreCase("Infinity") || defaultWDLValue.equalsIgnoreCase("Nan")) {
                // JSON does not recognize "Infinity" or "Nan" as valid float values (!), so we
                // need to treat them as String values
                return "\"" + defaultWDLValue + "\"";
            }
        }
        return defaultWDLValue;
    }

    /**
     * Parse the wdlArrayString and replace the elements with quoted elements
     * @param wdlArray
     * @return a wdlArrayString with each element quoted
     */
    protected String quoteWDLArrayValues(final String wdlArray) {
        final String wdlValues = wdlArray.substring(1, wdlArray.length() - 1);
        final String[] wdlValueArray = wdlValues.split(",");
        return String.format("[%s]",
                Arrays.stream(wdlValueArray).map(s -> String.format("\"%s\"", s.trim())).collect(Collectors.joining(",")));
    }

    /**
     * Given {@code candidateName}, transform/mangle the name if it is a WDL reserved word, otherwise
     * return {@code candidateName}.
     *
     * @param candidateName
     * @return mangled name if {@code candidateName} is a WDL reserved word, otherwise {@code candidateName}
     */
    protected String transformJavaNameToWDLName(final String candidateName) {
        return WDLTransforms.transformJavaNameToWDLName(candidateName);
    }

    /**
     * Given a Java collection class, return a String pair representing the string that should be replaced (the Java type),
     * and the string to substitute (the corresponding WDL type), i.e., for an argument with type Java List.class,
     * return the Pair ("List", "Array") to convert from the Java type to the corresponding WDL collection type.
     * @param argumentCollectionClass collection Class of the argument being converter
     * @return a String pair representing the original and replacement type text, or null if no conversion is available
     */
    protected Pair<String, String> transformToWDLCollectionType(final Class<?> argumentCollectionClass) {
        return WDLTransforms.transformToWDLCollectionType(argumentCollectionClass);
    }

    /**
     * Add any custom freemarker bindings discovered via custom javadoc tags. Subclasses can override this to
     * provide additional custom bindings.
     *
     * @param currentWorkUnit the work unit for the feature being documented
     */
    @Override
    protected void addCustomBindings(final DocWorkUnit currentWorkUnit) {
        super.addCustomBindings(currentWorkUnit);

        final RuntimeProperties rtProperties = currentWorkUnit.getClazz().getAnnotation(RuntimeProperties.class);
        if (rtProperties != null) {
            final Map<String, String> runtimePropertiesMap = new HashMap<>();
            runtimePropertiesMap.put(RUNTIME_PROPERTY_MEMORY, rtProperties.memory());
            runtimePropertiesMap.put(RUNTIME_PROPERTY_DISKS, rtProperties.disks());
            runtimePropertiesMap.put(RUNTIME_PROPERTY_CPU, Integer.toString(rtProperties.cpu()));
            runtimePropertiesMap.put(RUNTIME_PROPERTY_PREEMPTIBLE, Integer.toString(rtProperties.preEmptible()));
            runtimePropertiesMap.put(RUNTIME_PROPERTY_BOOT_DISK_SIZE_GB, Integer.toString(rtProperties.bootDiskSizeGb()));
            currentWorkUnit.setProperty(RUNTIME_PROPERTIES, runtimePropertiesMap);
        }
    }

    @Override
    protected void addExtraDocsBindings(final DocWorkUnit currentWorkUnit) {
        // skip extra docs since they don't affect WDL
    }

}
