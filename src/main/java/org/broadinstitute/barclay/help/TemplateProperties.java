package org.broadinstitute.barclay.help;

public class TemplateProperties {

    public final static String ARGUMENTS_POSITIONAL = "positional";

    // single argument map
    public final static String ARGUMENT_DEFAULT_VALUE = "defaultValue";
    public final static String ARGUMENT_NAME = "name";
    public final static String ARGUMENT_REQUIRED = "required";
    public final static String ARGUMENT_TYPE = "type";
    public final static String ARGUMENT_SUMMARY = "summary";

    //WDL Gen template property names
    public final static String WDL_ARGUMENT_ACTUAL_NAME = "actualArgName";
    public final static String WDL_ARGUMENT_INPUT_TYPE = "wdlinputtype";

    /**
     * Name of the top level freemarker map entry for runtime properties.
     *
     * Note that the property names used in this map will appear as workflow and task argument names in the
     * generated WDL, and should therefore not collide with any WDL reserved words.
     */
    public static final String WDL_WORKFLOW_PROPERTIES = "workflowProperties";
    /**
     * runtime memory property (stored in "workflowProperties", used to initialize arg value in JSON)
     */
    public static final String WDL_WORKFLOW_MEMORY = "memoryRequirements";
    /**
     * runtime disks property (stored in "workflowProperties", used to initialize arg value in JSON)
     */
    public static final String WDL_WORKFLOW_DISKS = "diskRequirements";
    /**
     * cpu property
     */
    public static final String WDL_WORKFLOW_CPU = "cpuRequirements";
    /**
     * bootDiskSizeGb property
     */
    public static final String WDL_WORKFLOW_BOOT_DISK_SIZE_GB = "bootdisksizegbRequirements";
    /**
     * preemptible property
     */
    public static final String WDL_WORKFLOW_PREEMPTIBLE = "preemptibleRequirements";
    /**
     * name of the top level freemarker map entry for runtime outputs
     */
    public static final String WDL_RUNTIME_OUTPUTS = "runtimeOutputs";
    /**
     * name of the top level freemarker map entry for required runtime outputs
     */
    public static final String WDL_REQUIRED_OUTPUTS = "requiredOutputs";
    /**
     * name of the top level freemarker map entry for required companion resources
     */
    public static final String WDL_REQUIRED_COMPANIONS = "requiredCompanions";
    /**
     * name of the top level freemarker map entry for optional companion resources
     */
    public static final String WDL_OPTIONAL_COMPANIONS = "optionalCompanions";

}
