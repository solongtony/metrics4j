package org.kairosdb.metrics4j.reporting;

import lombok.EqualsAndHashCode;
import lombok.ToString;

@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class StringValue extends MetricValue
{
	private final String m_value;

	public StringValue(String value)
	{
		super(TYPE_STRING);
		m_value = value;
	}

	@Override
	public String getValueAsString()
	{
		return m_value;
	}

}
