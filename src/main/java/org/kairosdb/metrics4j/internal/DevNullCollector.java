package org.kairosdb.metrics4j.internal;

import org.kairosdb.metrics4j.collectors.DoubleCollector;
import org.kairosdb.metrics4j.collectors.DurationCollector;
import org.kairosdb.metrics4j.collectors.LongCollector;
import org.kairosdb.metrics4j.collectors.ReportableMetric;
import org.kairosdb.metrics4j.reporting.ReportedMetric;

import java.time.Duration;

public class DevNullCollector implements LongCollector, DoubleCollector, DurationCollector, ReportableMetric
{
	@Override
	public void put(double value)
	{
	}

	@Override
	public void put(Duration duration)
	{
	}

	@Override
	public void put(long value)
	{
	}

	@Override
	public void reportMetric(ReportedMetric reportedMetric)
	{

	}
}
