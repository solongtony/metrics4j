package org.kairosdb.metrics4j.collectors.impl;

import lombok.EqualsAndHashCode;
import lombok.Setter;
import lombok.ToString;
import org.kairosdb.metrics4j.MetricsContext;
import org.kairosdb.metrics4j.collectors.Collector;
import org.kairosdb.metrics4j.collectors.LongCollector;
import org.kairosdb.metrics4j.reporting.LongValue;
import org.kairosdb.metrics4j.reporting.MetricReporter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@ToString
@EqualsAndHashCode
public class LongGauge implements LongCollector
{
	@EqualsAndHashCode.Exclude
	protected final AtomicLong m_gauge = new AtomicLong(0);

	@Setter
	protected boolean reset = false;

	public LongGauge(boolean reset)
	{
		super();
		this.reset = reset;
	}

	public LongGauge()
	{
		this(false);
	}


	@Override
	public void put(long value)
	{
		m_gauge.set(value);
	}

	@Override
	public void put(Instant time, long count)
	{
		put(count);
	}

	@Override
	public Collector clone()
	{
		return new LongGauge(reset);
	}

	@Override
	public void init(MetricsContext context)
	{

	}

	@Override
	public void reportMetric(MetricReporter metricReporter)
	{
		long value;

		if (reset)
			value = m_gauge.getAndSet(0);
		else
			value = m_gauge.get();

		metricReporter.put("gauge", new LongValue(value));
	}

	@Override
	public void setContextProperties(Map<String, String> contextProperties)
	{

	}

}
