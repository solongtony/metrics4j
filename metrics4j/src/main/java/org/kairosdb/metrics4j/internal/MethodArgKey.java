package org.kairosdb.metrics4j.internal;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.kairosdb.metrics4j.annotation.Key;
import org.kairosdb.metrics4j.configuration.ConfigurationException;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MethodArgKey implements ArgKey
{
	private final Method m_method;
	private final Object[] m_args;
	private final List<String> m_configPath;

	public MethodArgKey(Method method, Object[] args)
	{
		m_method = method;
		m_args = args;

		m_configPath = new ArrayList<>();
		String[] split = m_method.getDeclaringClass().getName().split("\\.");
		for (String s : split)
		{
			m_configPath.add(s);
		}

		m_configPath.add(m_method.getName());
	}

	public List<String> getConfigPath()
	{
		return m_configPath;
	}

	public Method getMethod()
	{
		return m_method;
	}


	public TagKey getTagKey()
	{
		if (m_args == null || m_args.length == 0)
			return TagKey.newBuilder().build();
		else
		{
			TagKey.Builder builder = TagKey.newBuilder();
			Annotation[][] parameterAnnotations = m_method.getParameterAnnotations();
			for (int i = 0; i < parameterAnnotations.length; i ++)
			{
				String tagKey = null;

				Annotation[] annotations = parameterAnnotations[i];
				for (Annotation annotation : annotations)
				{
					if (annotation instanceof Key)
					{
						Key key = (Key)annotation;
						tagKey = key.value();
					}
				}

				if (tagKey == null)
					throw new ConfigurationException("Parameter "+m_method.getParameters()[i].getName()+" on method "+m_method.getName()+" has not been annotated with @Key()");

				builder.addTag(tagKey, (String)m_args[i]);
			}

			TagKey tag = builder.build();
			//System.out.println(tag);

			return tag;
		}
	}

	@Override
	public String getMethodName()
	{
		return m_method.getName();
	}

	@Override
	public String getClassName()
	{
		return m_method.getDeclaringClass().getName();
	}

	@Override
	public String toString()
	{
		return m_method.getDeclaringClass().getName() + "." + m_method.getName();
	}

	/**
	 We intentionally only use the config path for equals and hash
	 @param o
	 @return
	 */
	@Override
	public boolean equals(Object o)
	{
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		MethodArgKey that = (MethodArgKey) o;
		return m_configPath.equals(that.m_configPath);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(m_configPath);
	}
}
