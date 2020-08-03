package org.broadinstitute.barclay.argparser;

import java.lang.annotation.*;

/**
 * Used to annotate @Arguments of a CommandLineProgram that are a workflow output. Used in the generation of WDL.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@Documented
public @interface WorkflowOutput {
    /**
     * @return array of names of required companion files that should travel with this resource. For example,
     * a (Genomic Reference) FASTA file might have two associated companion files that must be supplied as
     * arguments to the workflow, so the WorkflowOutput would use the attribute values:
     *
     * @WorkflowOutput(requiredCompanions = {"referenceDictionary", "referenceIndex" }
     *
     * The associated workflow and workflow input JSON (but not the task) generated by Barclay will contain two
     * additional optional parameters with these names, allowing callers to optionally provide name values for
     * the companion files.
     *
     * The additional arguments will be included in the workflow output section of the associated WDL for
     * resources that have output=true.
     */
    String[] requiredCompanions() default {};
}
