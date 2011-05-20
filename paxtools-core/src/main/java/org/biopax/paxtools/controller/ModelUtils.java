package org.biopax.paxtools.controller;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.biopax.paxtools.impl.BioPAXFactoryAdaptor;
import org.biopax.paxtools.io.BioPAXIOHandler;
import org.biopax.paxtools.io.SimpleIOHandler;
import org.biopax.paxtools.model.*;
import org.biopax.paxtools.model.level3.*;
import org.biopax.paxtools.model.level3.Process; //separate import required here!
import org.biopax.paxtools.util.ClassFilterSet;
import org.biopax.paxtools.util.Filter;
import org.biopax.paxtools.util.IllegalBioPAXArgumentException;

/**
 * An advanced BioPAX utility class that implements
 * several useful algorithms to extract root or child 
 * BioPAX elements, remove dangling, replace elements 
 * or identifiers, etc.
 * 
 * @author rodche
 *
 */
public class ModelUtils {
	private static final Log LOG = LogFactory.getLog(ModelUtils.class);
	
	/* 
	 * to ignore 'nextStep' property in most algorithms, 
	 * because it can eventually lead outside the current pathway, 
	 * and normally it (and pathwayOrder) is not necessary 
	 * for (step) processes to be reached (because they must be 
	 * listed in the pathwayComponent property as well).
	 */
	private static final Filter<PropertyEditor> nextStepFilter = new Filter<PropertyEditor>() {
		public boolean filter(PropertyEditor editor) {
			return !editor.getProperty().equals("nextStep")
				&& !editor.getProperty().equals("NEXT-STEP");
		}
	};
	
	private final Model model; // a model to hack ;)
	private final EditorMap editorMap;
	private final BioPAXIOHandler io;
	
	/**
	 * Controlled vocabulary terms for the RelationshipType 
	 * CV to be added with auto-generated/inferred comments
	 * and/or relationship xrefs.
	 * 
	 * We do not want to use "official" CV terms and IDs
	 * from the PSI-MI "cross-reference" branch for several reasons:
	 * - it's does not have terms we'd like and have those we'd not use
	 * - to distinguish auto-generated rel. xrefs from the original BioPAX data
	 * - hack: we also want put the related BioPAX element's URI into the xref's 'id' proerty
	 *   (this is not what the BioPAX standard officially regarding to use of xrefs)
	 * 
	 * @author rodche
	 *
	 */
	public static enum RelationshipType {
		PROCESS, // refers to a parent pathway or interaction
		ORGANISM,
		GENE, // term for, e.g., Entrez Gene rel. xrefs in protein references
		SEQUENCE, // e.g, to relate UniProt to RefSeq identifiers (incl. for splice variants...)
		; //TODO add more on use-case bases...
	}

	
	/**
	 * A comment (at least - prefix) to add to all generated objects
	 */
	public static final String COMMENT_FOR_GENERATED = "auto-generated";
	
	
	/* 
	 * URI prefix for auto-generated utility class objects
	 * (can be useful, for consistency, during, e.g.,
	 * data convertion, normalization, merge, etc.
	 */
	public static final String BIOPAX_URI_PREFIX = "urn:biopax:";

	
	/**
	 * This is to consistently create URI prefixes for  
	 * auto-generated/inferred Xref objects
	 * (except for PublicationXref, where creating of 
	 * something like, e.g., 'urn:miriam:pubmed:' is recommended). 
	 * 
	 * @param clazz
	 * @return
	 */
	public static String uriPrefixForGeneratedXref(Class<? extends Xref> clazz) {
		String prefix = BIOPAX_URI_PREFIX + clazz.getSimpleName() + ":";
		
		if(PublicationXref.class.equals(clazz) && LOG.isWarnEnabled()) {
			LOG.warn("uriPrefixForGeneratedXref: for a PublicationXref, " +
				"one should probably use a different prefix, " +
				"e.g., 'urn:miriam:pubmed:', etc. istead of this: "
				+ prefix);
		}
		
		return prefix;
	}
	

	/**
	 * Constructor.
	 * 
	 * @param model
	 */
	public ModelUtils(Model model) 
	{
		this.model = model;
		this.editorMap = SimpleEditorMap.get(model.getLevel());
		this.io = new SimpleIOHandler(model.getLevel());
		((SimpleIOHandler) this.io).mergeDuplicates(true);
	}
	
    /**
     * Replaces existing BioPAX element with another one,
     * of the same or possibly equivalent type (or null),
     * recursively updates all the references to it 
     * (parents' object properties).
     * 
     * @param existing
     * @param replacement
     */
    public void replace(final BioPAXElement existing, final BioPAXElement replacement) 
    {
    	if(replacement != null && 
    		existing.getModelInterface() != replacement.getModelInterface()) {
    		LOG.error("Cannot replace " + existing.getRDFId()
    				+ " (" + existing.getModelInterface().getSimpleName() 
    				+ ") with a different type object: "
    				+ replacement.getRDFId() + " (" 
    				+ replacement.getModelInterface().getSimpleName() + ")!");
    		return;
    	}
    	
    	// first, check if "shortcut" is possible
    	// nothing to replace?
    	if(!model.contains(existing)) {
    		if(LOG.isWarnEnabled())
    			LOG.warn("Model does not contain element " + existing);
    		return;
    	}
    	
    	/* ( here goes a tough concern ;) )
    	 * fail if the 'replacement' for 'existing' object
    	 * (with different ID) uses the same ID as another,
    	 * also different, model object! That means, 
    	 */
    	if(replacement != null) { 
    		String newId = replacement.getRDFId();
    		if(model.containsID(newId) // there is an object with the same ID,
    			&& !newId.equals(existing.getRDFId()) // and it is not the one to be replaced,
    			&& replacement != model.getByID(newId)) // and the replacement is not that, then - fail
    		{
    			throw new IllegalBioPAXArgumentException(
    				"There is another object with the same ID as replacemet's," +
    				" and it is not the same nor the one to be replaced! " +
    				"Try (decide) either to replace/remove that one first, " +
    				"or - get and use that one as the replacement instead.");
    		}
    	}
    	
    	// a visitor to replace the element in the model
		Visitor visitor = new Visitor() {
			@Override
			public void visit(BioPAXElement domain, Object range, Model model, PropertyEditor editor) {
				if(range instanceof BioPAXElement && range.equals(existing)) //it's Object.equals (not just RDFId)!
				{
					if(editor.isMultipleCardinality()) {
						if(replacement != null)
							editor.setValueToBean(replacement, domain);
						editor.removeValueFromBean(existing, domain);
					} else {
						editor.setValueToBean(replacement, domain);
					}
				}
			}
		};
		
		// run to update parent's properties with the new value ('replacement')
		EditorMap em = SimpleEditorMap.get(model.getLevel());
		Traverser traverser = new Traverser(em, visitor);
		for(BioPAXElement bpe : model.getObjects()) {
			traverser.traverse(bpe, null);
		}
		
		// remove the old one from the model
		model.remove(existing);

		// add the replacement
		if(replacement != null && !model.contains(replacement)) {
			model.add(replacement);
		}
    }

    
    /**
     * Deletes (recursively from the current model) 
     * only those child elements that would become "dangling" 
     * (not a property value of anything) if the parent 
     * element were (or already was) removed from the model.
     * 
     * @param parent
     */
    public void removeDependentsIfDangling(BioPAXElement parent) 
    {	
		// get the parent and all its children
		Fetcher fetcher = new Fetcher(editorMap);
		Model childModel = model.getLevel().getDefaultFactory().createModel();
		fetcher.fetch(parent, childModel);
		
		// copy all elements
		Set<BioPAXElement> others = new HashSet<BioPAXElement>(model.getObjects());
		
		// retain only those not the parent nor its child
		// (asymmetric difference)
		others.removeAll(childModel.getObjects());
		
		// traverse from each of "others" to exclude those from "children" that are used
		for(BioPAXElement e : others) {
			final BioPAXElement bpe = e;
			// define a special 'visitor'
			AbstractTraverser traverser = new AbstractTraverser(editorMap) 
			{
				@Override
				protected void visit(Object value, BioPAXElement parent, 
						Model m, PropertyEditor editor) 
				{
					if(value instanceof BioPAXElement 
							&& m.contains((BioPAXElement) value)) {
						m.remove((BioPAXElement) value); 
					}
				}
			};
			// check all biopax properties
			traverser.traverse(e, childModel);
		}
			
		// remove those left (would be dangling if parent were removed)!
		for (BioPAXElement o : childModel.getObjects()) {
			model.remove(o);
		}
    }
    
    
    /**
     * Replaces the RDFId of the BioPAX element
     * and in the current model. 
     * 
     * WARN: this hacker's method is to use with great care, 
     * because, if more than one BioPAX models share 
     * the (oldId) element, all these, except for current one,
     * will be broken (contain the updated element under its oldId) 
     * One can call {@link Model#repair()} to fix.
     * 
     * The method {@link #replace(BioPAXElement, BioPAXElement)} 
     * is much safer but less efficient in special cases, such as when 
     * one just needs to create/import one model, quickly update several or all
     * IDs, and save it to a file. 
     * 
     * @param oldId
     * @param newId
     */
    public void replaceID(String oldId, String newId) {
    	// fail if object with the newId or anything stored under this map key exists
		if(model.containsID(newId)) 
			throw new IllegalBioPAXArgumentException("Model already has ID: " + newId);

		InternalBioPAXFactory hackFactory = new InternalBioPAXFactory();
		
		BioPAXElement old = model.getByID(oldId);
		if(old != null) {
			// update if getById returns and object with the same ID
			if(oldId.equals(old.getRDFId())) {
				model.remove(old);
				hackFactory.setId(old, newId);
				model.add(old);
			} else {
				// for the broken model - skip
				if(LOG.isWarnEnabled())
					LOG.warn("Cannot replace ID. Element known by ID: " 
						+ oldId + ", in fact, has another ID: " 
						+ old.getRDFId());
			}
		} else {
			if(LOG.isWarnEnabled())
				LOG.warn("Cannot replace ID. Element is not found by ID: " + oldId);
		}
    }
    
    
    /* 
     * Extend the factory class to open up the setId method
     */
	private class InternalBioPAXFactory extends BioPAXFactoryAdaptor {
		@Override
		public BioPAXLevel getLevel() {
			return model.getLevel();
		}
		
		@Override
		protected <T extends BioPAXElement> T createInstance(Class<T> aClass,
				String id) throws ClassNotFoundException, InstantiationException,
				IllegalAccessException {
			throw new UnsupportedOperationException();
		}
		
		// this one we are going to need
		@Override
		public void setId(BioPAXElement bpe, String uri) {
			super.setId(bpe, uri);
		}
	}
	
	
	public Model getModel() {
		return model;
	}
	
	
	/**
	 * Finds a subset of "root" BioPAX objects of specific class (incl. sub-classes)
	 * 
	 * Note: however, such "root" elements may or may not be, a property of other
	 * elements, not included in the model.
	 * 
	 * @param filterClass 
	 * @return
	 */
	public <T extends BioPAXElement> Set<T> getRootElements(final Class<T> filterClass) 
	{
		// copy all such elements (initially, we think all are roots...)
		final Set<T> result = new HashSet<T>();
		result.addAll(model.getObjects(filterClass));
		
		// but we run from every element (all types)
		for(BioPAXElement e : model.getObjects()) {
			// define a special 'visitor'
			AbstractTraverser traverser = new AbstractTraverser(editorMap) 
			{
				@Override
				protected void visit(Object value, BioPAXElement parent, 
						Model model, PropertyEditor editor) {
					if(filterClass.isInstance(value)) 
						result.remove(value); 
				}
			};
			// check all biopax properties
			traverser.traverse(e, null);
		}
		
		return result;
	}
	
	/**
	 * Iteratively removes dangling elements
	 * of given type, e.g., utility class,  
	 * from current model.
	 */
	public <T extends BioPAXElement> void removeObjectsIfDangling(Class<T> clazz) 
	{
		Set<T> dangling = getRootElements(clazz);
		// get rid of dangling objects
		if(!dangling.isEmpty()) {
			if(LOG.isInfoEnabled()) 
				LOG.info(dangling.size() + " BioPAX utility objects " +
						"were/became dangling, and they "
						+ " will be deleted...");
			if(LOG.isDebugEnabled())
				LOG.debug("to remove (dangling after merge) :" + dangling);

			for(BioPAXElement thing : dangling) {
				model.remove(thing);
			}
			
			// some may have become dangling now, so check again...
			removeObjectsIfDangling(clazz);
		}
	}
	

	/**
	 * For the current (internal) model, this method 
	 * iteratively copies given property values 
	 * from parent BioPAX elements to children.
	 * If the property is multiple cardinality property, it will add
	 * new values, otherwise - it will set it only if was empty; 
	 * in both cases it won't delete/override existing values!
	 * 
	 * @see PropertyReasoner
	 * 
	 * @param property property name
	 * @param forClasses (optional) infer/set the property for these types only
	 */
	public void inferPropertyFromParent(final String property, 
			final Class<? extends BioPAXElement>... forClasses) 
	{		
		// for each ROOT element (puts a strict top-down order on the following)
		Set<BioPAXElement> roots = getRootElements(BioPAXElement.class);
		for(BioPAXElement bpe : roots) {
			PropertyReasoner reasoner = new PropertyReasoner(property, editorMap);
			reasoner.setDomains(forClasses);
			reasoner.inferPropertyValue(bpe);
		}
	}
		
	/**
	 * Cuts the BioPAX model off other models and BioPAX objects 
	 * by essentially performing write/read to/from OWL. 
	 * The resulting model contains new objects with same IDs 
	 * and have object properties "fixed", i.e., dangling values 
	 * become null/empty, and inverse properties (e.g. xrefOf)
	 * re-calculated. The original model is unchanged.
	 * 
	 * @return copy of the model
	 * @throws IOException 
	 */
	public Model writeRead()
	{
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		io.convertToOWL(model, baos);
		return io.convertFromOWL(new ByteArrayInputStream(baos.toByteArray()));
	}	

	
	/**
	 * Gets all the child BioPAX elements of a given BioPAX element
	 * (using the "tuned" {@link Fetcher}) and adds them to a 
	 * new model.
	 * 
	 * @param bpe
	 * @param filters property filters (e.g., for Fetcher to skip some properties). Default is to skip 'nextStep'.
	 * @return
	 */
	public Model getAllChildren(BioPAXElement bpe, Filter<PropertyEditor>... filters) {
		Model model = this.model.getLevel().getDefaultFactory().createModel();
		if(filters.length == 0	) {
			new Fetcher(editorMap, nextStepFilter).fetch(bpe, model);
		} else {
			new Fetcher(editorMap, filters).fetch(bpe, model);
		}
		model.remove(bpe); // remove the parent
		
		return model;
		//TODO limit to elements from this.model (add extra parameter)?
	}
	
	
	/**
	 * Gets direct children of a given BioPAX element
	 * and adds them to a new model.
	 * 
	 * @param bpe
	 * @return
	 */
	public Model getDirectChildren(BioPAXElement bpe) 
	{	
		Model model = this.model.getLevel().getDefaultFactory().createModel();
		
		AbstractTraverser traverser = new AbstractTraverser(editorMap, nextStepFilter) {
			@Override
			protected void visit(Object range, BioPAXElement domain,
					Model model, PropertyEditor editor) {
				if (range instanceof BioPAXElement 
						&& !model.contains((BioPAXElement) range)) {
					model.add((BioPAXElement) range);
				}
			}
		};
		
		traverser.traverse(bpe, model);
		
		return model;
		//TODO limit to elements from this.model (add extra parameter)?
	}
	
	
	/**
	 * Creates "process" (membership) relationship xrefs 
	 * for each child {@link Entity} if possible. 
	 * This is Level3 specific.
	 * 
	 * For each child {@link Entity} of every process 
	 * (of the type given by the second argument), creates a 
	 * relationship xref with the following properties:
	 * - db = provider (a name given by the second parameter)
	 * - id = the rdfId (URI) of the parent process
	 * - relationshipType = controlled vocabulary: "process" (MI:0359), urn:miriam:obo.mi:MI%3A0359
	 * - comment = "Auto-generated by Paxtools" (also added to the CV and its unification xref)
	 * 
	 * @param <T>
	 * @param processClass to relate entities with an interaction/pathway of this type 
	 */
	public <T extends Process> void generateEntityProcessXrefs(
			Class<T> processClass) 
	{	
		// use a special relationship CV;
		RelationshipTypeVocabulary cv = getTheRelatioshipTypeCV(RelationshipType.PROCESS);
		
		Set<T> processes = new HashSet<T>(model.getObjects(processClass)); //to avoid concurr. mod. ex.
		for(T ownerProc : processes) 
		{
			// prepare the xref to use in children
			String relXrefId = generateURIForRelationshipXref(COMMENT_FOR_GENERATED, ownerProc.getRDFId());
			RelationshipXref rx =  (RelationshipXref) model.getByID(relXrefId);
			if (rx == null) {
				rx = model.addNew(RelationshipXref.class, relXrefId);
				rx.addComment(COMMENT_FOR_GENERATED);
				rx.setDb(COMMENT_FOR_GENERATED);
				rx.setId(ownerProc.getRDFId());
				rx.setRelationshipType(cv);
			}
			
			// add the xref to all child entities
			Model childModel = getAllChildren(ownerProc);
			addRelationshipXref(childModel.getObjects(Entity.class), rx);
		}
	}

	
	/**
	 * Adds the relationship xref to every entity in the set.
	 * 
	 * @param elements
	 * @param x
	 */
	private void addRelationshipXref(Set<? extends Entity> elements, RelationshipXref x) 
	{
		//TODO shall we ignore entities within Evidence, ExperimentalForm, etc.?
		for(Entity ent : elements) {
				ent.addXref(x);
		}
	}
	
	
	/**
	 * Auto-generates organism relationship xrefs - 
	 * for BioPAX entities that do not have such property (but their children do), 
	 * such as of Interaction, Protein, Complex, DNA, Protein, etc. classes.
	 * 
	 * Infers organisms in two steps:
	 * 
	 * 1. add organisms as relationship xrefs 
	 *    of {@link SimplePhysicalEntity} objects (from EntityReference objects), 
	 *    except for smallmolecules.
	 * 2. infer organism information recursively via all children, 
	 *    but only when children are also Entity objects (not utility classes)
	 *    (equivalently, this can be achieved by collecting all the children first, 
	 *     though not visiting properties who's range is a sub-class of UtilityClass)
	 * 
	 */
	public void generateEntityOrganismXrefs() 
	{
		// The first pass (physical entities)
		Set<SimplePhysicalEntity> simplePEs = // prevents concurrent mod. exceptions
			new HashSet<SimplePhysicalEntity>(model.getObjects(SimplePhysicalEntity.class));
		for(SimplePhysicalEntity spe : simplePEs) {
			//get the organism value (BioSource) from the ER; create/add rel. xrefs to the spe
			EntityReference er = spe.getEntityReference();
			// er can be generic (member ers), so -
			Set<BioSource> organisms = getOrganismsFromER(er);
			addOrganismXrefs(spe, organisms);
		}
		
		// use a special filter for the (child elements) fetcher
		Filter<PropertyEditor> entityRangeFilter = new Filter<PropertyEditor>() {
			@Override
			public boolean filter(PropertyEditor editor) {
				// values are of Entity sub-class -
				return Entity.class.isAssignableFrom(editor.getRange());
			}
		};
		
		/* 
		 * The second pass (all entities, particularly - 
		 * Pathway, Gene, Interaction, Complex and generic physical entities
		 */
		Set<Entity> entities = new HashSet<Entity>(model.getObjects(Entity.class));
		for(Entity entity : entities) {
			Set<BioSource> organisms = new HashSet<BioSource>();
			
			//If the entity has "true" organism property (it's Gene or Pathway), collect it
			addOrganism(entity, organisms);
		
			/* collect its children (- of Entity class only, 
			 * i.e., won't traverse into UtilityClass elements's properties)
			 * 
			 * Note: although Stoichiometry.physicalEntity, 
			 *       ExperimentalForm.experimentalFormEntity, 
			 *       and PathwayStep.stepProcess are (only) examples 
			 *       when an Entity can be value of a UtilityClass
			 *       object's property, we have to skip these 
			 *       utility classes (with their properties) anyway.
			 */
			Model submodel = getAllChildren(entity, entityRangeFilter);
			// now, collect organism values from the children entities 
			// (using both property 'organism' and rel.xrefs created above!)
			for(Entity e : submodel.getObjects(Entity.class)) {
				//skip SM
				if(e instanceof SmallMolecule)
					continue;
				
				//has "true" organism property? (a Gene or Pathway?) Collect it.
				addOrganism(e, organisms);
				// check in rel. xrefs
				for(Xref x : e.getXref()) 
				{
					if(x instanceof RelationshipXref) {
						RelationshipXref rx = (RelationshipXref) x;
					
						RelationshipTypeVocabulary cv = rx.getRelationshipType();
						if(cv != null && cv.getTerm().contains(RelationshipType.ORGANISM.name())) {
							//previously, xref.id was set to a BioSource' ID!
							assert(rx.getId() != null);
							BioSource bs = (BioSource) model.getByID(rx.getId());
							assert(bs != null);
							organisms.add(bs);
						}
					}
				}
			}
			
			// add all the organisms (xrefs) to this entity
			addOrganismXrefs(entity, organisms);
		}
	}

	
	/**
	 * Returns a set of organism of the entity reference.
	 * If it is a generic entity reference (has members), 
	 * - will recursively collect all the organisms from 
	 * its members.  
	 * 
	 * @param er
	 * @return
	 */
	private Set<BioSource> getOrganismsFromER(EntityReference er) {
		Set<BioSource> organisms = new HashSet<BioSource>();
		if(er instanceof SequenceEntityReference) {
			BioSource organism = ((SequenceEntityReference) er).getOrganism();
			if(organism != null) {
				organisms.add(organism);
			}
			
			if(!er.getMemberEntityReference().isEmpty()) {
				for(EntityReference mer : er.getMemberEntityReference()) {
					organisms.addAll(
							getOrganismsFromER(mer)
					);
				}
			}
		}
		return organisms;
	}

	
	/**
	 * Adds entity's organism value (if applicable and it has any) to the set.
	 * 
	 * @param entity
	 * @param organisms
	 */
	private void addOrganism(BioPAXElement entity, Set<BioSource> organisms) {
		PropertyEditor editor = editorMap.getEditorForProperty("organism",
				entity.getModelInterface());
		if (editor != null) {
			Object o = editor.getValueFromBean(entity);
			if(o != null) {
				Set<BioSource> seto = (Set<BioSource>) o;
				organisms.addAll(seto);
			}
		}
	}

	
	/**
	 * Generates a relationship xref for each 
	 * organism (BioSource) in the list and adds them to the entity.
	 * 
	 * @param entity
	 * @param organisms
	 */
	private void addOrganismXrefs(Entity entity, Set<BioSource> organisms) {
		// create/find a RelationshipTypeVocabulary with term="ORGANISM"
		RelationshipTypeVocabulary cv = getTheRelatioshipTypeCV(RelationshipType.ORGANISM);
		// add xref(s) to the entity
		for(BioSource organism : organisms) {
			String db = COMMENT_FOR_GENERATED; 
			String id = organism.getRDFId();
			String relXrefId = generateURIForRelationshipXref(db, id);
			RelationshipXref rx =  (RelationshipXref) model.getByID(relXrefId);
			if (rx == null) {
				rx = model.addNew(RelationshipXref.class, relXrefId);
				rx.setRelationshipType(cv);
				rx.addComment(COMMENT_FOR_GENERATED);
				rx.setDb(db);
				rx.setId(id);
			}
			entity.addXref(rx);
		}
	}

	
	/**
	 * Finds in the model or creates a new special RelationshipTypeVocabulary 
	 * controlled vocabulary with the term value defined by the argument (enum).
	 * 
	 * @param relationshipType
	 * @return
	 */
	private RelationshipTypeVocabulary getTheRelatioshipTypeCV(RelationshipType relationshipType) 
	{
		String cvId = BIOPAX_URI_PREFIX + "RelationshipTypeVocabulary:"
				+ relationshipType;
		// try to get from the model first
		RelationshipTypeVocabulary cv = (RelationshipTypeVocabulary) model
				.getByID(cvId);
		if (cv == null) { // one instance per model to be created
			cv = model.addNew(RelationshipTypeVocabulary.class, cvId);
			cv.addTerm(relationshipType.name());
			cv.addComment(COMMENT_FOR_GENERATED);
			
			/* disabled: in favor of custom terms from RelationshipType -
			String uxid = "urn:biopax:UnificationXref:MI_MI%3A0359";
			UnificationXref ux = (UnificationXref) model.getByID(uxid);
			if(ux == null) {
				ux = model.addNew(UnificationXref.class, uxid);
				ux.addComment(COMMENT_FOR_GENERATED);
				ux.setDb("MI");
				ux.setId("MI:0359");
			}
			cv.addXref(ux);
			*/
		}
		
		return cv;
	}
	
	
	/**
	 * Builds a "normalized" RelationshipXref URI.
	 * 
	 * @param db
	 * @param id
	 * @return new ID (URI); not null (unless it's a bug :))
	 * 
	 */
	public static String generateURIForRelationshipXref(String db, String id) 
	{
		String rdfid;
		String prefix = uriPrefixForGeneratedXref(RelationshipXref.class); 
			
		// add the local part of the URI encoded -
		try {
			rdfid = prefix + URLEncoder
				.encode(db.trim() + "_" + id.trim(), "UTF-8")
					.toUpperCase();
		} catch (UnsupportedEncodingException e) {
			if(LOG.isWarnEnabled())
				LOG.warn("ID UTF-8 encoding failed! " +
					"Using the platform default (deprecated method).", e);
			rdfid = prefix + URLEncoder
				.encode(db.trim() + "_" + id.trim()).toUpperCase();
		}

		return rdfid;
	}
}
