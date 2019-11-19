package org.kairosdb.metrics4j;

import org.kairosdb.metrics4j.collectors.Collector;
import org.kairosdb.metrics4j.collectors.LongCollector;
import org.kairosdb.metrics4j.configuration.MetricConfig;
import org.kairosdb.metrics4j.internal.ArgKey;
import org.kairosdb.metrics4j.internal.CollectorContainer;
import org.kairosdb.metrics4j.internal.CustomArgKey;
import org.kairosdb.metrics4j.internal.DoubleLambdaCollectorAdaptor;
import org.kairosdb.metrics4j.internal.LambdaArgKey;
import org.kairosdb.metrics4j.internal.LongLambdaCollectorAdaptor;
import org.kairosdb.metrics4j.internal.MethodArgKey;
import org.kairosdb.metrics4j.internal.SourceInvocationHandler;
import org.kairosdb.metrics4j.collectors.MetricCollector;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 Need to put methods to set various components programatically.

 In kairos I want to set the reporter as a plugin to kairos that sends
 events using the event bus, but the configuration will reference this
 reporter by name in config even though no class will be defined in config.

 */
public class MetricSourceManager
{
	private static Map<Class, SourceInvocationHandler> s_invocationMap = new ConcurrentHashMap<>();

	private static MetricConfig s_metricConfig;

	/**
	 For testing purposes only, not to be used in production
	 @param config
	 */
	public static void setMetricConfig(MetricConfig config)
	{
		s_metricConfig = config;
		s_invocationMap.clear();
	}

	public static MetricConfig getMetricConfig()
	{
		if (s_metricConfig == null)
		{
			ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
			InputStream propertiesInputStream = contextClassLoader.getResourceAsStream("metrics4j.properties");
			InputStream configInputStream = contextClassLoader.getResourceAsStream("metrics4j.xml");
			try
			{
				s_metricConfig = MetricConfig.parseConfig(propertiesInputStream, configInputStream);
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		}

		return s_metricConfig;
	}

	public static void registerMetricCollector(MetricCollector collector)
	{
		ArgKey key = new CustomArgKey(collector);

		getMetricConfig().getContext().assignCollector(key, collector, new HashMap<>(), new HashMap<>(), null);
	}

	public static <T> T getSource(Class<T> tClass)
	{
		//Need to create invocationHandler for tClass using
		//invocation handler needs to setup for each method
		//in tClass an appropriate collectors object and registers the collectors
		//object in some central registry.

		//On some schedule the collectors objects in the registry and scrapped
		//for their data and that data is sent to any configured sinks endpoints (kairos, influx, etc..)
		//This all needs to be based on configuration that is dynamically loaded
		//so that it can change at runtime

		//Extra challenge - ability to have sinks endpoints get data at different
		//resolutions.  ex one endpoint gets data every minute and another endpoint
		//gets data every 10 min.  Challenge is in the collectors objects if they reset
		//any state or not

		//todo need to do some validation on tClass, makes ure all methods only take strings and are annotated with Keys

		InvocationHandler handler = s_invocationMap.computeIfAbsent(tClass, (klass) -> {
			MetricConfig metricConfig = getMetricConfig();
			if (metricConfig.isDumpMetrics())
			{
				String className = klass.getName();
				Method[] methods = klass.getMethods();
				for (Method method : methods)
				{
					metricConfig.addDumpSource(className+"."+method.getName());
				}
			}
			return new SourceInvocationHandler(getMetricConfig());
		});

		//not sure if we should cache proxy instances or create new ones each time.
		Object proxyInstance = Proxy.newProxyInstance(tClass.getClassLoader(), new Class[]{tClass},
				handler);

		return (T)proxyInstance;
	}

	/**
	 For registering a class to gather metrics from.  Methods annotated with
	 Reported will be called
	 @param o
	 @param tags
	 */
	public static void export(Object o, Map<String, String> tags)
	{
		//todo check object annotated with @Reported and add collector for them
	}

	public static void export(String name, Map<String, String> tags, String help, LongSupplier supplier)
	{
		ArgKey key = new LambdaArgKey(name);
		MetricConfig metricConfig = getMetricConfig();

		MetricCollector collector = new LongLambdaCollectorAdaptor(supplier);

		MetricsContext context = metricConfig.getContext();

		Map<String, String> configTags = metricConfig.getTagsForKey(key);
		if (tags != null)
			configTags.putAll(tags);

		context.assignCollector(key, collector, configTags, metricConfig.getPropsForKey(key), null);
	}

	public static void export(String name, Map<String, String> tags, String help, DoubleSupplier supplier)
	{
		ArgKey key = new LambdaArgKey(name);
		MetricConfig metricConfig = getMetricConfig();
		MetricsContext context = metricConfig.getContext();

		MetricCollector collector = new DoubleLambdaCollectorAdaptor(supplier);

		Map<String, String> configTags = metricConfig.getTagsForKey(key);
		if (tags != null)
			configTags.putAll(tags);

		context.assignCollector(key, collector, configTags, metricConfig.getPropsForKey(key), null);
	}

	/**
		This method is provided for unit test purposes.  It lets you define
		a collectors object for a specific metric call.  See the unit tests
		in ReporterFactoryTest to see how to use this method.
	*/
	public static <T> T setCollectorForSource(MetricCollector stats, Class<T> reporterClass)
	{
		MetricConfig metricConfig = getMetricConfig();

		SourceInvocationHandler handler = s_invocationMap.computeIfAbsent(reporterClass, (klass) -> new SourceInvocationHandler(metricConfig));

		Object proxyInstance = Proxy.newProxyInstance(reporterClass.getClassLoader(), new Class[]{reporterClass},
				(proxy, method, args) -> {
					handler.setCollector(new MethodArgKey(method, args), stats);
					return null;
				});

		return (T)proxyInstance;
	}

	public MetricsContext getMetricsContext()
	{
		return null;
	}

}
