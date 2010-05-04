package org.biopax.paxtools.impl.level3;

import org.biopax.paxtools.impl.BioPAXElementImpl;
import org.biopax.paxtools.model.level3.RnaRegion;
import org.hibernate.search.annotations.Indexed;

import javax.persistence.Entity;
import javax.persistence.Transient;


/**
 */
@Entity
@Indexed(index=BioPAXElementImpl.SEARCH_INDEX_FOR_ENTITY)
class RnaRegionImpl extends NucleicAcidImpl implements RnaRegion
{
// --------------------- Interface BioPAXElement ---------------------

    @Override @Transient
	public Class<? extends RnaRegion> getModelInterface()
	{
		return RnaRegion.class;
	}

}
