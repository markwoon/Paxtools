package org.biopax.paxtools.pattern.miner;

import org.biopax.paxtools.model.BioPAXElement;
import org.biopax.paxtools.model.Model;
import org.biopax.paxtools.model.level3.XReferrable;
import org.biopax.paxtools.model.level3.Xref;
import org.biopax.paxtools.pattern.Match;
import org.biopax.paxtools.pattern.Searcher;
import org.biopax.paxtools.pattern.util.HGNC;

import java.util.*;

/**
 * Searches a model and generates SIF network using the pattern matches.
 *
 * @author Ozgun Babur
 */
public class SIFSearcher
{
	/**
	 * SIF miners to use.
	 */
	private List<SIFMiner> miners;

	/**
	 * SIF types to mine.
	 */
	private Set<SIFType> types;

	/**
	 * Used for getting an ID out of BioPAX elements.
	 */
	private IDFetcher idFetcher;

	/**
	 * Constructor with miners.
	 * @param types sif types
	 */
	public SIFSearcher(SIFType... types)
	{
		this(null, types);
	}

	/**
	 * Constructor with ID fetcher and miners.
	 * @param types sif types
	 */
	public SIFSearcher(IDFetcher idFetcher, SIFType... types)
	{
		this.idFetcher = idFetcher;
		this.types = new HashSet<SIFType>(Arrays.asList(types));
		this.miners = new ArrayList<SIFMiner>();

		for (SIFType type : types)
		{
			switch (type)
			{
				case CONTROLS_STATE_CHANGE:
					miners.add(new ControlsStateChangeMiner());
					miners.add(new ControlsStateChangeButIsParticipantMiner());
					break;
				case CONTROLS_EXPRESSION:
					miners.add(new ControlsExpressionMiner());
					miners.add(new ControlsExpressionWithConvMiner());
					break;
				case CONTROLS_DEGRADATION:
					miners.add(new DegradesMiner());
					break;
				case CONSECUTIVE_CATALYSIS:
					miners.add(new ConsecutiveCatalysisMiner(null));
					break;
				case IN_SAME_COMPLEX:
					miners.add(new InSameComplexMiner());
					break;
				case INTERACTS_WITH:
					miners.add(new InSameComplexMiner());
					break;
				default: throw new RuntimeException("There is an unhandled sif type: " + type);
			}
		}

		if (idFetcher == null)
		{
			this.idFetcher = new IDFetcher()
			{
				@Override
				public String fetchID(BioPAXElement ele)
				{
					if (ele instanceof XReferrable)
					{
						for (Xref xr : ((XReferrable) ele).getXref())
						{
							String db = xr.getDb();
							if (db != null)
							{
								db = db.toLowerCase();
								if (db.startsWith("hgnc"))
								{
									String id = xr.getId();
									if (id != null)
									{
										String symbol = HGNC.getSymbol(id);
										if (symbol != null && !symbol.isEmpty())
										{
											return symbol;
										}
									}
								}
							}
						}
					}

					return null;

				}
			};
		}
	}

	/**
	 * Searches the given model with the contained miners.
	 * @param model model to search
	 * @return sif interactions
	 */
	public Set<SIFInteraction> searchSIF(Model model)
	{
		Map<SIFInteraction, SIFInteraction> map = new HashMap<SIFInteraction, SIFInteraction>();

		for (SIFMiner miner : miners)
		{
			Map<BioPAXElement,List<Match>> matches = Searcher.search(model, miner.getPattern());

			for (List<Match> matchList : matches.values())
			{
				for (Match m : matchList)
				{
					SIFInteraction sif = miner.createSIFInteraction(m, idFetcher);

					if (sif != null && sif.hasIDs() && types.contains(sif.type))
					{
						if (map.containsKey(sif))
						{
							SIFInteraction existing = map.get(sif);
							existing.mergeWith(sif);
						}
						else map.put(sif, sif);
					}
				}
			}
		}
		return new HashSet<SIFInteraction>(map.values());
	}
}
