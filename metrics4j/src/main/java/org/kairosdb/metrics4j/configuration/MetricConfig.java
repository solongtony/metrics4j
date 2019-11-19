package org.kairosdb.metrics4j.configuration;

import org.kairosdb.metrics4j.MetricsContext;
import org.kairosdb.metrics4j.collectors.MetricCollector;
import org.kairosdb.metrics4j.formatters.Formatter;
import org.kairosdb.metrics4j.internal.ArgKey;
import org.kairosdb.metrics4j.internal.CollectorContainer;
import org.kairosdb.metrics4j.internal.MetricsContextImpl;
import org.kairosdb.metrics4j.internal.SinkQueue;
import org.kairosdb.metrics4j.internal.TriggerMetricCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.SAXException;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MetricConfig
{
	private static Logger log = LoggerFactory.getLogger(MetricConfig.class);

	private static final Pattern formatPattern = Pattern.compile("\\$\\{([^\\}]*)\\}");

	private Properties m_properties = new Properties();

	private final Map<List<String>, Map<String, String>> m_mappedTags;
	private final Map<List<String>, Map<String, String>> m_mappedProps;
	private final Map<List<String>, String> m_mappedMetricNames;

	private final MetricsContextImpl m_context;
	private final List<Closeable> m_closeables;

	private boolean m_shutdownOverride = false;
	private boolean m_dumpMetrics = false;
	private String m_dumpFile;
	private Document m_dumpTree;


	private static Element getFirstElement(Element parent, String tag)
	{
		Node ret = null;

		NodeList list = parent.getElementsByTagName(tag);
		if (list != null && list.getLength() != 0)
		{
			ret = list.item(0);
		}

		return (Element)ret;
	}

	private String formatValue(String value)
	{
		Matcher matcher = formatPattern.matcher(value);
		StringBuilder sb = new StringBuilder();

		int endLastMatch = 0;
		while (matcher.find())
		{
			int start = matcher.start();
			int end = matcher.end();

			if (start != endLastMatch)
			{
				sb.append(value, endLastMatch, start);
			}

			String token = matcher.group(1);

			//todo look for values from properties file and from env
			sb.append(m_properties.getProperty(token, "${"+token+"}"));

			endLastMatch = end;
		}

		sb.append(value.substring(endLastMatch));

		return sb.toString();
	}

	private void processElement(Element classConfig)
	{
		NamedNodeMap attributes = classConfig.getAttributes();

		for (int i = 0; i < attributes.getLength(); i++)
		{
			Attr attribute = (Attr)attributes.item(i);

			attribute.setValue(formatValue(attribute.getValue()));
		}


		NodeList childNodes = classConfig.getChildNodes();

		for (int i = 0; i < childNodes.getLength(); i++)
		{
			Node child = childNodes.item(i);
			if (child instanceof Element)
				processElement((Element)child);

			if (child instanceof Text)
			{
				Text txtChild = (Text) child;
				txtChild.replaceWholeText(formatValue(txtChild.getWholeText()));
			}
		}
	}

	private <T> T loadClass(Element classConfig) throws JAXBException
	{
		T ret = null;
		String className = classConfig.getAttribute("class");

		try
		{
			ClassLoader pluginLoader = MetricConfig.class.getClassLoader();

			processElement(classConfig);

			String pluginFolder = classConfig.getAttribute("folder");

			if (!pluginFolder.isEmpty())
			{
				pluginLoader = new PluginClassLoader(getJarsInPath(pluginFolder), pluginLoader);
			}

			Class<?> pluginClass = pluginLoader.loadClass(className);
			JAXBContext context = JAXBContext.newInstance(pluginClass);

			Unmarshaller unmarshaller = context.createUnmarshaller();

			ret = (T) unmarshaller.unmarshal(classConfig);
		}
		catch (ClassNotFoundException | MalformedURLException e)
		{
			throw new ConfigurationException("Unable to locate class '"+className+"' for configuration element '"+classConfig.getTagName()+"'");
		}

		return ret;
	}

	private static URL[] getJarsInPath(String path) throws MalformedURLException
	{
		List<URL> jars = new ArrayList<URL>();
		File libDir = new File(path);
		File[] fileList = libDir.listFiles();
		if(fileList != null)
		{
			for (File f : fileList)
			{
				if (f.getName().endsWith(".jar"))
				{
					jars.add(f.toURI().toURL());
				}
			}
		}

		//System.out.println(jars);
		return jars.toArray(new URL[0]);
	}

	private <T> void registerStuff(Element parent, String childName, BiConsumer<String, T> register) throws JAXBException
	{
		NodeList childList = parent.getElementsByTagName(childName);

		for (int i = 0; i < childList.getLength(); i++)
		{
			Element classElement = (Element)childList.item(i);
			T classInstance = loadClass(classElement);

			register.accept(classElement.getAttribute("name"), classInstance);

			if (classInstance instanceof Closeable)
			{
				m_closeables.add((Closeable)classInstance);
			}
		}
	}

	/*package*/ static List<String> appendSourceName(List<String> parent, String child)
	{
		List<String> copy = new ArrayList<>(parent);

		String[] splitNames = child.split("\\.");

		copy.addAll(Arrays.asList(splitNames));
		return copy;
	}

	/**
	 Recursively parse through the sources elements
	 @param root
	 @param path
	 */
	private void parseSources(Element root, List<String> path)
	{
		if (root == null)
			throw new ConfigurationException("No 'sources' element in your configuration");

		NodeList childNodes = root.getChildNodes();

		if (childNodes != null)
		{
			for (int i = 0; i < childNodes.getLength(); i++)
			{
				Node node = childNodes.item(i);
				String nodeName = node.getNodeName();

				if (node instanceof Element)
				{
					if ("source".equals(nodeName))
					{
						Element element = (Element) node;
						String name = element.getAttribute("name");
						String metricName = element.getAttribute("metric_name");

						List<String> newPath = appendSourceName(path, name);
						if (!metricName.isEmpty())
							m_mappedMetricNames.put(newPath, metricName);

						parseSources(element, newPath);
					}
					else if ("sink".equals(nodeName)) //todo add some attribute to a sink that prevents inheriting sinks
					{
						//need to map to a list of sinks as there can be more than one
						Element sinkElm = (Element) node;
						String ref = sinkElm.getAttribute("ref");

						m_context.addSinkToPath(ref, path);
					}
					else if ("collector".equals(nodeName))
					{
						Element collectorElm = (Element) node;
						String ref = collectorElm.getAttribute("ref");

						m_context.addCollectorToPath(ref, path);
					}
					else if ("formatter".equals(nodeName))
					{
						Element collectorElm = (Element) node;
						String ref = collectorElm.getAttribute("ref");
						String sink = collectorElm.getAttribute("sink");

						m_context.addFormatterToPath(ref, path);
					}
					else if ("trigger".equals(nodeName))
					{
						Element triggerElm = (Element) node;
						String ref = triggerElm.getAttribute("ref");

						m_context.addTriggerToPath(ref, path);
					}
					else if ("tag".equals(nodeName))
					{
						Element tagElm = (Element) node;

						String key = tagElm.getAttribute("key");
						String value = tagElm.getAttribute("value");

						Map<String, String> pathTags = m_mappedTags.computeIfAbsent(path, (k) -> new HashMap<>());
						pathTags.put(key, value);
					}
					else if ("prop".equals(nodeName))
					{
						Element propElm = (Element) node;

						String key = propElm.getAttribute("key");
						String value = propElm.getAttribute("value");

						Map<String, String> pathProps = m_mappedProps.computeIfAbsent(path, (k) -> new HashMap<>());
						pathProps.put(key, value);
					}
					else
					{
						throw new ConfigurationException("Unknown configuration element: " + nodeName);
					}
				}


			}
		}
	}

	/**
	 Parse through the root level elements of the metrics4j xml file
	 @param configInputStream
	 @return
	 @throws ParserConfigurationException
	 @throws IOException
	 @throws SAXException
	 */
	public static MetricConfig parseConfig(InputStream propertiesInputStream, InputStream configInputStream) throws ParserConfigurationException, IOException, SAXException
	{
		//todo break up this method so it can be built in parts by unit tests
		MetricsContextImpl context = new MetricsContextImpl();
		MetricConfig ret = new MetricConfig(context);


		if (propertiesInputStream != null)
		{
			Properties props = new Properties();
			props.load(propertiesInputStream);

			ret.setProperties(props);
		}

		//todo add system properties and add env

		if (configInputStream != null)
		{
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document configDoc = db.parse(configInputStream);

			Element root = configDoc.getDocumentElement();

			try
			{
				//Parse out the sinks
				Element sinks = getFirstElement(root, "sinks");
				if (sinks != null)
					ret.registerStuff(sinks, "sink", context::registerSink);

				Element collectors = getFirstElement(root, "collectors");
				if (collectors != null)
					ret.registerStuff(collectors, "collector", context::registerCollector);

				Element formatters = getFirstElement(root, "formatters");
				if (formatters != null)
					ret.registerStuff(formatters, "formatter", context::registerFormatter);

				Element triggers = getFirstElement(root, "triggers");
				if (triggers != null)
					ret.registerStuff(triggers, "trigger", context::registerTrigger);

				//todo parse through sources and add them to a map
				Element sources = getFirstElement(root, "sources");
				if (sources != null)
				{
					String dumpFile = sources.getAttribute("dump_file");
					if (!dumpFile.isEmpty())
					{
						ret.m_dumpFile = dumpFile;
						ret.m_dumpMetrics = true;
						DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
						DocumentBuilder builder = builderFactory.newDocumentBuilder();
						ret.m_dumpTree = builder.newDocument();
						Element dumpRoot = ret.m_dumpTree.createElement("metrics4j");
						ret.m_dumpTree.appendChild(dumpRoot);
						Element dumpSources = ret.m_dumpTree.createElement("sources");
						dumpRoot.appendChild(dumpSources);
					}
					ret.parseSources(sources, new ArrayList<>());
				}
			}
			catch (JAXBException e)
			{
				log.error("Error parsing config file", e);
				throw new RuntimeException(e);
			}
		}

		return ret;
	}


	/*package*/
	public MetricConfig(MetricsContextImpl context)
	{
		m_context = context;
		m_closeables = new ArrayList<>();
		m_mappedTags = new HashMap<>();
		m_mappedProps = new HashMap<>();
		m_mappedMetricNames = new HashMap<>();


		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				if (!m_shutdownOverride)
					shutdown();
			}
		}));
	}

	private void shutdown()
	{
		log.debug("Shutdown called for Metrics4j");
		for (Closeable closeable : m_closeables)
		{
			try
			{
				closeable.close();
			}
			catch (Exception e)
			{
				log.error("Error closing "+closeable.getClass().getName(), e);
			}
		}

		if (m_dumpFile != null)
		{
			log.debug("Writing dump file {}", m_dumpFile);
			try
			{
				Transformer tf = TransformerFactory.newInstance().newTransformer();
				tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
				tf.setOutputProperty(OutputKeys.INDENT, "yes");
				tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
				FileWriter out = new FileWriter(m_dumpFile);
				tf.transform(new DOMSource(m_dumpTree), new StreamResult(out));
			}
			catch (TransformerConfigurationException e)
			{
				e.printStackTrace();
			}
			catch (TransformerException e)
			{
				e.printStackTrace();
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
		}
	}

	public ShutdownHookOverride getShutdownHookOverride()
	{
		m_shutdownOverride = true;
		return MetricConfig.this::shutdown;
	}







	public String getMetricNameForKey(ArgKey key)
	{
		return m_mappedMetricNames.get(key.getConfigPath());
	}







	public MetricsContext getContext()
	{
		return m_context;
	}

	public void setProperties(Properties properties)
	{
		m_properties = properties;
	}


	/**
	 Returns a map of tags that you can modify
	 @param argKey
	 @return
	 */
	public Map<String, String> getTagsForKey(ArgKey argKey)
	{
		Map<String, String> ret = new HashMap<>();
		List<String> configPath = argKey.getConfigPath();
		for (int i = configPath.size(); i >= 0; i--)
		{
			List<String> searchPath = new ArrayList<>(configPath.subList(0, i));
			Map<String, String> pathTags = m_mappedTags.getOrDefault(searchPath, new HashMap<>());

			for (String key : pathTags.keySet())
			{
				ret.putIfAbsent(formatValue(key), formatValue(pathTags.get(key)));
			}
		}

		return ret;
	}

	public Map<String, String> getPropsForKey(ArgKey argKey)
	{
		Map<String, String> ret = new HashMap<>();
		List<String> configPath = argKey.getConfigPath();
		for (int i = configPath.size(); i >= 0; i--)
		{
			List<String> searchPath = new ArrayList<>(configPath.subList(0, i));
			Map<String, String> pathProps = m_mappedProps.getOrDefault(searchPath, new HashMap<>());

			for (String key : pathProps.keySet())
			{
				ret.putIfAbsent(formatValue(key), formatValue(pathProps.get(key)));
			}
		}

		return ret;
	}

	public boolean isDumpMetrics()
	{
		return m_dumpMetrics;
	}

	/**
	 Adds a source that will be dumped out on shutdown.
	 @param src
	 */
	public void addDumpSource(String src)
	{
		String[] split = src.split("\\.");

		Element root = m_dumpTree.getDocumentElement(); //gets metrics4j element
		root = (Element)root.getFirstChild(); //gets sources element

		for (String path : split)
		{
			Element nextChild = null;
			NodeList childNodes = root.getChildNodes();

			for (int i = 0; i < childNodes.getLength(); i++)
			{
				Element child = (Element)childNodes.item(i);
				if (child.getAttribute("name").equals(path))
				{
					nextChild = child;
					break;
				}
			}

			if (nextChild == null)
			{
				nextChild = m_dumpTree.createElement("source");
				nextChild.setAttribute("name", path);
				root.appendChild(nextChild);
			}

			root = nextChild;
		}
	}
}
