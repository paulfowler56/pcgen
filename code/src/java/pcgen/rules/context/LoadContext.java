/*
 * Copyright 2007 (C) Tom Parker <thpr@users.sourceforge.net>
 * 
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 * 
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package pcgen.rules.context;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import pcgen.cdom.base.CDOMObject;
import pcgen.cdom.base.CategorizedCDOMObject;
import pcgen.cdom.base.PrimitiveChoiceFilter;
import pcgen.cdom.base.PrimitiveChoiceSet;
import pcgen.cdom.enumeration.ListKey;
import pcgen.cdom.enumeration.Type;
import pcgen.cdom.inst.ObjectCache;
import pcgen.cdom.reference.ReferenceManufacturer;
import pcgen.core.Campaign;
import pcgen.core.Equipment;
import pcgen.core.PObject;
import pcgen.core.WeaponProf;
import pcgen.core.prereq.Prerequisite;
import pcgen.core.utils.ParsingSeparator;
import pcgen.persistence.PersistenceLayerException;
import pcgen.persistence.lst.CampaignSourceEntry;
import pcgen.persistence.lst.output.prereq.PrerequisiteWriter;
import pcgen.rules.persistence.ChoiceSetLoadUtilities;
import pcgen.rules.persistence.TokenLibrary;
import pcgen.rules.persistence.TokenSupport;
import pcgen.rules.persistence.token.DeferredToken;
import pcgen.rules.persistence.token.ParseResult;
import pcgen.util.Logging;
import pcgen.util.StringPClassUtil;

public abstract class LoadContext
{

	private static final Class<String> STRING_CLASS = String.class;

	public final AbstractListContext list;

	public final AbstractObjectContext obj;

	public final ReferenceContext ref;
	
	private final List<Campaign> campaignList = new ArrayList<Campaign>();

	public LoadContext(ReferenceContext rc, AbstractListContext lc, AbstractObjectContext oc)
	{
		if (rc == null)
		{
			throw new IllegalArgumentException("ReferenceContext cannot be null");
		}
		if (lc == null)
		{
			throw new IllegalArgumentException("ListContext cannot be null");
		}
		if (oc == null)
		{
			throw new IllegalArgumentException("ObjectContext cannot be null");
		}
		ref = rc;
		list = lc;
		obj = oc;
	}

	private int writeMessageCount = 0;

	public void addWriteMessage(String string)
	{
		Logging.errorPrint("!!" + string);
		/*
		 * TODO Need to find a better solution for what happens during write...
		 */
		writeMessageCount++;
	}

	public int getWriteMessageCount()
	{
		return writeMessageCount;
	}

	/**
	 * Sets the extract URI. This is a shortcut for setting the URI on both the
	 * graph and obj members.
	 * 
	 * @param extractURI
	 */
	public void setExtractURI(URI extractURI)
	{
		getObjectContext().setExtractURI(extractURI);
		ref.setExtractURI(extractURI);
		getListContext().setExtractURI(extractURI);
	}

	/**
	 * Sets the source URI. This is a shortcut for setting the URI on both the
	 * graph and obj members.
	 * 
	 * @param sourceURI
	 */
	public void setSourceURI(URI sourceURI)
	{
		this.sourceURI = sourceURI;
		getObjectContext().setSourceURI(sourceURI);
		ref.setSourceURI(sourceURI);
		getListContext().setSourceURI(sourceURI);
		clearStatefulInformation();
	}

	/*
	 * Get the type of context we're running in (either Editor or Runtime)
	 */
	public abstract String getContextType();

	public AbstractObjectContext getObjectContext()
	{
		return obj;
	}

	public AbstractListContext getListContext()
	{
		return list;
	}

	public void commit()
	{
		getListContext().commit();
		getObjectContext().commit();
	}

	public void rollback()
	{
		getListContext().rollback();
		getObjectContext().rollback();
	}

	public void resolveReferences()
	{
		ref.resolveReferences();
	}

	public void resolveDeferredTokens()
	{
		for (DeferredToken<? extends CDOMObject> token : TokenLibrary
				.getDeferredTokens())
		{
			processRes(token);
		}
	}

	private <T extends CDOMObject> void processRes(DeferredToken<T> token)
	{
		Class<T> cl = token.getDeferredTokenClass();
		Collection<? extends ReferenceManufacturer> mfgs = ref
				.getAllManufacturers();
		for (ReferenceManufacturer<? extends T> rm : mfgs)
		{
			if (cl.isAssignableFrom(rm.getReferenceClass()))
			{
				for (T po : rm.getAllObjects())
				{
					token.process(this, po);
				}
			}
		}
	}

	private final TokenSupport support = new TokenSupport();

	public <T extends CDOMObject> PrimitiveChoiceSet<T> getChoiceSet(
			Class<T> poClass, String value)
	{
		try
		{
			return ChoiceSetLoadUtilities.getChoiceSet(this, poClass, value);
		}
		catch (ParsingSeparator.GroupingMismatchException e)
		{
			Logging.errorPrint("Group Mismatch in getting ChoiceSet: "
					+ e.getMessage());
			return null;
		}
	}

	public <T extends CDOMObject> PrimitiveChoiceFilter<T> getPrimitiveChoiceFilter(
			Class<T> cl, String key)
	{
		return ChoiceSetLoadUtilities.getPrimitive(this, cl, key);
	}
			

	public <T> ParseResult processSubToken(T cdo, String tokenName,
			String key, String value) throws PersistenceLayerException
	{
		return support.processSubToken(this, cdo, tokenName, key, value);
	}

	public <T extends CDOMObject> boolean processToken(T derivative,
			String typeStr, String argument) throws PersistenceLayerException
	{
		return support.processToken(this, derivative, typeStr, argument);
	}
	
	public <T extends CDOMObject> void unconditionallyProcess(T cdo, String key, String value)
	{
		try
		{
			if (processToken(cdo, key, value))
			{
				commit();
			}
			else
			{
				rollback();
				Logging.replayParsedMessages();
			}
			Logging.clearParseMessages();
		}
		catch (PersistenceLayerException e)
		{
			Logging.errorPrint("Error in token parse: "
					+ e.getLocalizedMessage());
		}
	}

	public <T> String[] unparse(T cdo, String tokenName)
	{
		return support.unparse(this, cdo, tokenName);
	}

	public <T> Collection<String> unparse(T cdo)
	{
		return support.unparse(this, cdo);
	}

//	public <T extends CDOMObject> PrimitiveChoiceSet<?> getChoiceSet(
//			CDOMObject cdo, String key, String val)
//			throws PersistenceLayerException
//	{
//		return support.getChoiceSet(this, cdo, key, val);
//	}

	public <T extends CDOMObject> T cloneConstructedCDOMObject(T cdo, String newName)
	{
		T newObj = obj.cloneConstructedCDOMObject(cdo, newName);
		ref.importObject(newObj);
		return newObj;
	}

	private static final PrerequisiteWriter PREREQ_WRITER =
			new PrerequisiteWriter();

	public String getPrerequisiteString(Collection<Prerequisite> prereqs)
	{
		try
		{
			return PREREQ_WRITER.getPrerequisiteString(prereqs);
		}
		catch (PersistenceLayerException e)
		{
			addWriteMessage("Error writing Prerequisite: " + e);
			return null;
		}
	}

	public Map<Class<?>, Set<String>> typeMap = new HashMap<Class<?>, Set<String>>();
	
	public void buildTypeLists()
	{
		Set<String> typeSet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
		typeMap.put(WeaponProf.class, typeSet);
		for (WeaponProf wp : ref.getConstructedCDOMObjects(WeaponProf.class))
		{
			for (Type t : wp.getTrueTypeList(false))
			{
				typeSet.add(t.toString());
			}
		}
		typeSet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
		typeMap.put(Equipment.class, typeSet);
		for (Equipment e : ref.getConstructedCDOMObjects(Equipment.class))
		{
			for (Type t : e.getTrueTypeList(false))
			{
				typeSet.add(t.toString());
			}
		}
	}
	
	public Collection<String> getTypes(Class<?> cl)
	{
		Set<String> returnSet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
		Set<String> set = typeMap.get(cl);
		if (set != null)
		{
			returnSet.addAll(set);
		}
		return returnSet;
	}
	
	public boolean containsType(Class<?> cl, String type)
	{
		Set<String> set = typeMap.get(cl);
		return set != null && set.contains(type);
	}

	private URI sourceURI;

	public CampaignSourceEntry getCampaignSourceEntry(Campaign source, String value)
	{
		return CampaignSourceEntry.getNewCSE(source, sourceURI, value);
	}

	private CDOMObject stateful;

	public void clearStatefulInformation()
	{
		stateful = null;
	}

	public boolean addStatefulToken(String s) throws PersistenceLayerException
	{
		int colonLoc = s.indexOf(':');
		if (colonLoc == -1)
		{
			Logging.errorPrint("Found invalid stateful token: " + s);
			return false;
		}
		if (stateful == null)
		{
			stateful = new ObjectCache();
		}
		return processToken(stateful, s.substring(0, colonLoc), s
			.substring(colonLoc + 1));
	}

	public void addStatefulInformation(CDOMObject target)
	{
		if (stateful != null)
		{
			target.overlayCDOMObject(stateful);
		}
	}

	public void setLoaded(List<Campaign> campaigns)
	{
		campaignList.clear();
		campaignList.addAll(campaigns);
	}

	public boolean isTypeHidden(Class<?> cl, String type)
	{
		for (Campaign c : campaignList)
		{
			List<String> hiddentypes = c.getListFor(ListKey.getKeyFor(
					STRING_CLASS, "HIDDEN_" + cl.getSimpleName()));
			if (hiddentypes != null)
			{
				for (String s : hiddentypes)
				{
					if (s.equalsIgnoreCase(type))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	public abstract boolean consolidate();

	public ReferenceManufacturer<? extends CDOMObject> getManufacturer(String firstToken)
	{
		int equalLoc = firstToken.indexOf('=');
		String className;
		String categoryName;
		if (equalLoc != firstToken.lastIndexOf('='))
		{
			Logging
					.log(Logging.LST_ERROR,
							"  Error encountered: Found second = in ObjectType=Category");
			Logging.log(Logging.LST_ERROR,
					"  Format is: ObjectType[=Category]|Key[|Key] value was: "
							+ firstToken);
			Logging.log(Logging.LST_ERROR, "  Valid ObjectTypes are: "
					+ StringPClassUtil.getValidStrings());
			return null;
		}
		else if ("FEAT".equals(firstToken))
		{
			className = "ABILITY";
			categoryName = "FEAT";
		}
		else if (equalLoc == -1)
		{
			className = firstToken;
			categoryName = null;
		}
		else
		{
			className = firstToken.substring(0, equalLoc);
			categoryName = firstToken.substring(equalLoc + 1);
		}
		Class<? extends CDOMObject> c = StringPClassUtil.getClassFor(className);
		if (c == null)
		{
			Logging.log(Logging.LST_ERROR, "Unrecognized ObjectType: "
					+ className);
			return null;
		}
		ReferenceManufacturer<? extends CDOMObject> rm;
		if (CategorizedCDOMObject.class.isAssignableFrom(c))
		{
			if (categoryName == null)
			{
				Logging
						.log(Logging.LST_ERROR,
								"  Error encountered: Found Categorized Type without =Category");
				Logging.log(Logging.LST_ERROR,
						"  Format is: ObjectType[=Category]|Key[|Key] value was: "
								+ firstToken);
				Logging.log(Logging.LST_ERROR, "  Valid ObjectTypes are: "
						+ StringPClassUtil.getValidStrings());
				return null;
			}
			
			rm = ref.getManufacturer(((Class) c), categoryName);
			if (rm == null)
			{
				Logging.log(Logging.LST_ERROR, "  Error encountered: "
						+ className + " Category: " + categoryName
						+ " not found");
				return null;
			}
		}
		else
		{
			if (categoryName != null)
			{
				Logging
						.log(Logging.LST_ERROR,
								"  Error encountered: Found Non-Categorized Type with =Category");
				Logging.log(Logging.LST_ERROR,
						"  Format is: ObjectType[=Category]|Key[|Key] value was: "
								+ firstToken);
				Logging.log(Logging.LST_ERROR, "  Valid ObjectTypes are: "
						+ StringPClassUtil.getValidStrings());
				return null;
			}
			rm = ref.getManufacturer(c);
		}
		return rm;
	}

	public void performDeferredProcessing(CDOMObject cdo)
	{
		for (DeferredToken<? extends CDOMObject> token : TokenLibrary
				.getDeferredTokens())
		{
			if (token.getDeferredTokenClass().isAssignableFrom(cdo.getClass()))
			{
				processDeferred(cdo, token);
			}
		}
	}

	private <T extends CDOMObject> void processDeferred(CDOMObject cdo,
			DeferredToken<T> token)
	{
		token.process(this, ((T) cdo));
	}

	public <T extends PObject> void addTypesToList(T cdo)
	{
		Set<String> typeSet = typeMap.get(cdo.getClass());
		for (Type t : cdo.getTrueTypeList(false))
		{
			typeSet.add(t.toString());
		}
	}
}
