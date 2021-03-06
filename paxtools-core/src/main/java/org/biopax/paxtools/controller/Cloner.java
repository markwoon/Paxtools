package org.biopax.paxtools.controller;

import org.biopax.paxtools.model.BioPAXElement;
import org.biopax.paxtools.model.BioPAXFactory;
import org.biopax.paxtools.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Specifically "Clones" the BioPAX elements set
 * (traverses to obtain dependent elements), 
 * puts them to the new model using the visitor and traverser framework;
 * ignores elements that are not in the source list (compare to {@link Fetcher}).
 *
 * Note: it's not thread safe (use a separate Cloner instance per thread or web container method)
 *
 * @see org.biopax.paxtools.controller.Visitor
 * @see org.biopax.paxtools.controller.Traverser
 */
public class Cloner implements Visitor
{
	private static final Logger LOG = LoggerFactory.getLogger(Cloner.class);
	
	Traverser traverser;
	private BioPAXFactory factory;
	private Model targetModel;

	public Cloner(EditorMap map, BioPAXFactory factory)
	{
		this.factory = factory;
		this.traverser = new Traverser(map, this);
		this.targetModel = null;
	}

	
	/**
	 * For each element from the 'toBeCloned' list,
	 * it creates a copy in the new model, setting all 
	 * the data properties; however, object property values
	 * that refer to BioPAX elements not in 'toBeCloned' list
	 * are ignored.
	 * 
	 * @param source model
	 * @param toBeCloned elements to clone
	 * @return a new model containing the cloned biopax objects
	 */
	public synchronized Model clone(Model source, Set<BioPAXElement> toBeCloned)
	{
		targetModel = factory.createModel();

		for (BioPAXElement bpe : new HashSet<BioPAXElement>(toBeCloned))
		{
			// make a copy (all properties are empty except for ID)
			if(targetModel.containsID(bpe.getUri())) {
				throw new RuntimeException("There're different objects having the same URI"
						+ " in the target/cloned model and input set:" + bpe.getUri());
			} else {
				targetModel.addNew(bpe.getModelInterface(), bpe.getUri());
			}
		}

		// a hack to avoid unnecessary checks for the valid sub-model being cloned, 
		// and warnings when the Cloner copies BPS.stepProcess values, 
		// and there is a Conversion among them (-always unless stepConversion is null).
		AbstractPropertyEditor.checkRestrictions.set(false);
		
		for (BioPAXElement bpe : toBeCloned)
		{
			traverser.traverse(bpe, source);
		}
		
		AbstractPropertyEditor.checkRestrictions.set(true); //back to the default mode

		return targetModel;
	}

// --------------------- Interface Visitor ---------------------

	public void visit(BioPAXElement domain, Object range, Model model, PropertyEditor editor)
	{
		BioPAXElement targetDomain = targetModel.getByID(domain.getUri());
		
		if (range instanceof BioPAXElement)
		{
			BioPAXElement bpe = (BioPAXElement) range;
			BioPAXElement existing = targetModel.getByID(bpe.getUri());
			//set the property value if the value is already present in the target 
			if (existing != null) {
				editor.setValueToBean(existing, targetDomain);
			}
		}
		else
		{
			editor.setValueToBean(range, targetDomain);
		}
	}
}



