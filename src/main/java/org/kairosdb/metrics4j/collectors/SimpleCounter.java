package org.kairosdb.metrics4j.collectors;

import org.kairosdb.metrics4j.reporting.LongValue;
import org.kairosdb.metrics4j.reporting.ReportedMetric;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleCounter implements LongCollector, ReportableMetric
{
	private final AtomicLong m_count = new AtomicLong(0);


	public void put(long count)
	{
		m_count.addAndGet(count);
	}

	public Collector reset()
	{
		m_count.set(0);
		return this;
	}


	@Override
	public void reportMetric(ReportedMetric reportedMetric)
	{
		reportedMetric.setFields(Collections.singletonMap("count", new LongValue(m_count.longValue())));
	}
}
