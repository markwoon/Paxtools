package org.biopax.paxtools.impl.level3;

import org.biopax.paxtools.impl.BioPAXElementImpl;
import org.biopax.paxtools.model.level3.ProteinReference;
import org.hibernate.search.annotations.Indexed;

import javax.persistence.Entity;
import javax.persistence.Transient;

@Entity
@Indexed(index=BioPAXElementImpl.SEARCH_INDEX_FOR_UTILILTY_CLASS)
class ProteinReferenceImpl extends SequenceEntityReferenceImpl
        implements ProteinReference
{

    @Transient
    public Class<? extends ProteinReference> getModelInterface()
    {
        return ProteinReference.class;
    }
}
