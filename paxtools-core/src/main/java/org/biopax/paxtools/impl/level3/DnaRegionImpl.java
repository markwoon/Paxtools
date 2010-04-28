package org.biopax.paxtools.impl.level3;

import org.biopax.paxtools.model.level3.DnaRegion;


/**
 */
class DnaRegionImpl extends SimplePhysicalEntityImpl implements DnaRegion
{
// ------------------------ INTERFACE METHODS ------------------------


// --------------------- Interface BioPAXElement ---------------------

    @Override
	public Class<? extends DnaRegion> getModelInterface()
	{
		return DnaRegion.class;
	}

}
