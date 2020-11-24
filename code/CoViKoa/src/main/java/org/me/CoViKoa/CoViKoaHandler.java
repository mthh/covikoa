package org.me.CoViKoa;

import org.apache.jena.geosparql.configuration.GeoSPARQLConfig;
import org.apache.jena.geosparql.configuration.GeoSPARQLOperations;
import org.apache.jena.ontology.*;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.reasoner.ValidityReport;
import org.apache.jena.sparql.expr.aggregate.AggregateRegistry;
import org.apache.jena.sparql.graph.NodeConst;
import org.apache.jena.update.*;
import org.apache.jena.util.FileUtils;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.topbraid.jenax.util.JenaUtil;
import org.topbraid.shacl.rules.RuleUtil;

import java.io.*;
import java.util.*;

import static org.me.CoViKoa.AggregateGeomFunction.*;


/**
 * Class allowing to load project ontologies, the derivation model and to instrument the CoViKoa reasoning
 * (using SHACL internally).
 *
 * @author Matthieu Viry
 * @version 0.0.1
 */
public class CoViKoaHandler {
    private final static Logger logger = LoggerFactory.getLogger(CoViKoaHandler.class);
    /**
     * Data model on which we will work.
     *
     * This is the ontological model containing the base individuals and on which
     * the new individuals will be inserted.
     *
     */
    private final OntModel dataModel;
//    private final InfModel dataModel;
    /**
     * Model containing SHACL shapes and rules.
     */
    private final Model shapesModel;
    /**
     * Model hosting the new triples generated by SHACL rules.
     */
    private Model newTriples;
    /**
     * The last validation report produced.
     *
     * SHACL validation is triggered each time triples are inserted in the data model.
     * This variable contains the last validation report produced.
     */
    private Model lastValidationReport;

    /**
     * The URI of the triple acting as a marker to determine the current rule execution set.
     */
    private String uriMarkerRuleExecutionMarker = null;
    private Boolean applyGeoSpaqlInferencing;

    public CoViKoaHandler(String[] dataFiles, String derivationFile, Boolean applyGeoSpaqlInferencing) throws FileNotFoundException {
        // Setup :
        //   - the ARQ engine
        //   - GeoSPARQL functionnalities / spatial index
        //   - register our custom geo aggregation functions
        org.apache.jena.query.ARQ.init();
        GeoSPARQLConfig.setupMemoryIndex();

        AggregateRegistry.register("http://lig-tdcge.imag.fr/steamer/covikoa/geo-agg#Union", unionAccumulatorFactory, NodeConst.nodeMinusOne);
        AggregateRegistry.register("http://lig-tdcge.imag.fr/steamer/covikoa/geo-agg#Intersection", intersectionAccumulatorFactory, NodeConst.nodeMinusOne);
        AggregateRegistry.register("http://lig-tdcge.imag.fr/steamer/covikoa/geo-agg#Extent", envelopeAccumulatorFactory, NodeConst.nodeMinusOne);

        this.applyGeoSpaqlInferencing = applyGeoSpaqlInferencing;
        // Read the files containing our base instances (they are importing our ontologies)
        // and the demo/tests instances (if any) and merge them in one Model
        this.dataModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
        if (dataFiles != null) {
            for (String dataFilePath : dataFiles) {
                dataModel.read(new FileInputStream(dataFilePath), null, FileUtils.langTurtle);
            }
        }

        // Validate the logical consistency of the Semantic Data Model
        this.validateModel();

        // We want to reuse the derivation model and the generated rules from this object...
        RulesGenerator rgen = new RulesGenerator(derivationFile);
        //dataModel.read(new FileInputStream(derivationFile), null, FileUtils.langTurtle);
        dataModel.add(rgen.derivationModel);
        this.shapesModel = rgen.generatedRules;


        // Now that everything is loaded we can start a first run of the inference system...
        this.recursiveInferFromRulesAndUpdateDS();
    }
    private void createRuleExecutionIndiv() {
        // Retrieve the current datetime
        Calendar cal = GregorianCalendar.getInstance();
        Literal dtValue = dataModel.createTypedLiteral(cal);

        // Create the RuleExecutionMarker (we keep track of its URI because we will delete it later)
        this.uriMarkerRuleExecutionMarker = "urn:id:RuleExecutionMarker-" + UUID.randomUUID();
        Resource resource = dataModel.createResource( this.uriMarkerRuleExecutionMarker );
        Resource ruleExecMarker = dataModel.getResource("http://lig-tdcge.imag.fr/steamer/covikoa/derivation#RuleExecutionMarker");
        Property p = dataModel.getProperty("http://www.w3.org/2006/time#inXSDDateTime" );
        dataModel.add(dataModel.createStatement(resource, RDF.type, ruleExecMarker));
        dataModel.add(dataModel.createStatement(resource, p, dtValue));

        // Create a prov:Generation (it wont be deleted later so we can keep track of
        // the number of generations + knowing during rule execution that some knowledge
        // was infered during a previous generation).
        Resource thisgen = dataModel.createResource("urn:id:Genereation-" + UUID.randomUUID());
        Resource ruleExecActivity = dataModel.getResource("http://lig-tdcge.imag.fr/steamer/covikoa/derivation#RuleExecution");
        Resource prov_gen = dataModel.getResource("http://www.w3.org/ns/prov#Generation");
        Property prov_attime = dataModel.getProperty("http://www.w3.org/ns/prov#atTime");
        Property prov_activity = dataModel.getProperty("http://www.w3.org/ns/prov#activity");
        dataModel.add(dataModel.createStatement(thisgen, RDF.type, prov_gen));
        dataModel.add(dataModel.createStatement(thisgen, prov_attime, dtValue));
        dataModel.add(dataModel.createStatement(thisgen, prov_activity, ruleExecActivity));
    }

    private void deleteRuleExecutionIndiv() {
        if (uriMarkerRuleExecutionMarker != null) {
            Individual r = dataModel.getIndividual(uriMarkerRuleExecutionMarker);
            dataModel.removeAll(r, null, (RDFNode) null);
        }
    }

    public void recursiveInferFromRulesAndUpdateDS() {
        // We want to keep track of the number of iterations necessary to have produced all the triplets
        // (the inference "loop" will only stop after the last pass didn't produce a triplet).
        long startTime;
        long estimatedTime;
        long n_iter = 0;
        String logmsg;
        long cumultime = 0;
        long cumulnewtriples = 0;

        // Create the RuleExecutionMarker and the Generation individuals
        this.createRuleExecutionIndiv();
        while (true) {
            logmsg = "";
            n_iter += 1;
            startTime = System.currentTimeMillis();
            // Fetch the inference graph according to our SHACL rules
            long nbInferedTriples = this.inferFromRules();
            estimatedTime = System.currentTimeMillis() - startTime;
            logmsg += "Executing SHACL rules ...\n" +
                    "\tSize of newTriples: " + nbInferedTriples +
                    "\n\tInference: " + estimatedTime + "ms";
            cumultime += estimatedTime;

            if (nbInferedTriples > 0) {
                // If any new triplets, add them to the data model
                // after having applied GeoSPARQL inferencing on them if needed ...
                if (applyGeoSpaqlInferencing) {
                    startTime = System.currentTimeMillis();
                    GeoSPARQLOperations.applyInferencing(this.newTriples);
                    estimatedTime = System.currentTimeMillis() - startTime;
                    logmsg += "\n\tApplying GeoSPARQL inferencing: " + estimatedTime + "ms";
                    cumultime += estimatedTime;
                }
                cumulnewtriples += nbInferedTriples;
                startTime = System.currentTimeMillis();
                this.addNewTriplesDataModel();
                estimatedTime = System.currentTimeMillis() - startTime;
                cumultime += estimatedTime;
                logmsg += "\n\tUnion new triples and DataModel: " + estimatedTime + "ms";
                logger.warn(logmsg);
            } else {
                // .. otherwise we are done here.
                logger.warn(logmsg);
                break;
            }
        }
        // Delete the RuleExecutionMarker (the Generation individual stays in the graph)
        this.deleteRuleExecutionIndiv();
        logger.warn("Exited recursiveInferFromRulesAndUpdateDS after " + n_iter + " iterations (took a total of " + cumultime + "ms for " + cumulnewtriples + " new triples).");
    }

    public long inferFromRules(){
        this.newTriples = JenaUtil.createDefaultModel();
        RuleUtil.executeRules(dataModel, shapesModel, newTriples, null);
        return newTriples.size();
    }

    private void addNewTriplesDataModel() {
        this.dataModel.add(newTriples);
    }

    public Model getNewTriples() {
        return newTriples;
    }

    public Model getDataModel() {
        return dataModel;
    }

    public Model getShapesModel() {
        return shapesModel;
    }

    public Model getLastValidationReport() {
        return lastValidationReport;
    }

    private ResultSet queryModel(Model model, String queryString) throws QueryException {
        Query query = QueryFactory.create(queryString);
        QueryExecution qexec = QueryExecutionFactory.create(query, model);
        ResultSet results = qexec.execSelect();
        return results;
    }

    private String queryModelJSON(Model model, String queryString) {
        try {
            ResultSet results = queryModel(this.dataModel, queryString);
            ByteArrayOutputStream stringWriter = new ByteArrayOutputStream();
            ResultSetFormatter.outputAsJSON(stringWriter, results);
            return stringWriter.toString();
        } catch (QueryException e) {
            e.printStackTrace();
            return "{\"response\":\"Error\",\"message\":\"Unexpected error while querying the DataModel\"}";
        }
    }

    private List<String> queryModelToList(Model model, String queryString) {
        List<String> qLineResult = new ArrayList<>();
        try {
            ResultSet results = queryModel(this.dataModel, queryString);
            while (results.hasNext())
            {
                QuerySolution soln = results.nextSolution() ;
                System.out.println(soln.toString());
                qLineResult.add(soln.toString());
            }
        } catch (QueryException e) {
            e.printStackTrace();
        }
        return qLineResult;
    }

    public String queryDataModelJSON(String queryString) {
        return queryModelJSON(this.dataModel, queryString);
    }
    public String queryNewTriplesJSON(String queryString) {
        return queryModelJSON(this.newTriples, queryString);
    }
    public List<String> queryDataModel(String queryString) {
        return queryModelToList(this.dataModel, queryString);
    }
    public List<String> queryNewTriples(String queryString) {
        return queryModelToList(this.newTriples, queryString);
    }

    private String updateModel(Model model, String updateString) {
        try {
            UpdateAction.parseExecute(updateString, model);
        } catch (UpdateException e) {
            e.printStackTrace();
            return "{\"response\":\"Error\",\"message\":\"Unexpected error while updating the DataModel\"}";
        }
        recursiveInferFromRulesAndUpdateDS();
        return "{\"response\":\"OK\"}";
    }

    public String updateDataModel(String queryString) {
        return updateModel(this.dataModel, queryString);
    }

    public void validateModel() {
        ValidityReport validity = this.dataModel.validate();
        if (validity.isValid()) {
            logger.warn("Validity data model = OK");
        } else {
            logger.warn("Validity data model = Conflicts");
            for (Iterator i = validity.getReports(); i.hasNext(); ) {
                logger.warn(" - " + i.next());
            }
        }
    }
}
