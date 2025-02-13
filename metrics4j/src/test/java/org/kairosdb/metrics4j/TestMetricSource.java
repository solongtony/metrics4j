package org.kairosdb.metrics4j;

import org.kairosdb.metrics4j.annotation.Key;
import org.kairosdb.metrics4j.collectors.DurationCollector;
import org.kairosdb.metrics4j.collectors.LongCollector;

public interface TestMetricSource
{
	LongCollector reportSize(@Key("host") String host);

	LongCollector reportStatus(@Key("success") boolean success);

	DurationCollector reportTime(@Key("host") String host);
}
