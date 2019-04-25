package org.kairosdb.metrics4j.internal;

import org.kairosdb.metrics4j.collectors.ReportableMetric;

public class StatsContainer
{
	private final Object m_statsObject;
	private final String m_className;
	private final String m_methodName;

	public StatsContainer(ReportableMetric statsObject, String klass, String method)
	{
		m_statsObject = statsObject;
		m_className = klass;
		m_methodName = method;
	}

	public Object getStatsObject()
	{
		return m_statsObject;
	}

	public String getClassName()
	{
		return m_className;
	}

	public String getMethodName()
	{
		return m_methodName;
	}
}
