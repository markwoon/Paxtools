package org.biopax.paxtools.impl.level3;

import org.biopax.paxtools.impl.BioPAXElementImpl;
import org.biopax.paxtools.model.level3.Degradation;
import org.hibernate.search.annotations.Indexed;

import javax.persistence.Entity;
import javax.persistence.Transient;

@Entity
@Indexed(index=BioPAXElementImpl.SEARCH_INDEX_FOR_ENTITY)
class DegradationImpl extends ConversionImpl implements Degradation
{
    @Transient
	public Class<? extends Degradation> getModelInterface()
    {
        return Degradation.class;
    }

}
