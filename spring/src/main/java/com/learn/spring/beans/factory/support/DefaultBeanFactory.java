package com.learn.spring.beans.factory.support;

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.learn.spring.beans.BeanDefinition;
import com.learn.spring.beans.BeansException;
import com.learn.spring.beans.PropertyValue;
import com.learn.spring.beans.SimpleTypeConverter;
import com.learn.spring.beans.factory.BeanCreationException;
import com.learn.spring.beans.factory.BeanFactoryAware;
import com.learn.spring.beans.factory.NoSuchBeanDefinitionException;
import com.learn.spring.beans.factory.config.InstantiationAwareBeanPostProcessor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.learn.spring.beans.factory.config.BeanPostProcessor;
import com.learn.spring.beans.factory.config.DependencyDescriptor;
import com.learn.spring.util.ClassUtils;

/**
 * BeanFactory默认实现
 * @author Elliot
 */
public class DefaultBeanFactory  extends AbstractBeanFactory
	implements BeanDefinitionRegistry{

	private static final Log logger = LogFactory.getLog(DefaultBeanFactory.class);

	private List<BeanPostProcessor> beanPostProcessors = new ArrayList<BeanPostProcessor>();
	
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<String, BeanDefinition>(64);

	private ClassLoader beanClassLoader;
	
	public DefaultBeanFactory() {
		
	}
	@Override
    public void addBeanPostProcessor(BeanPostProcessor postProcessor){
		this.beanPostProcessors.add(postProcessor);
	}

	@Override
    public List<BeanPostProcessor> getBeanPostProcessors() {
		return this.beanPostProcessors;
	}

	@Override
    public void registerBeanDefinition(String beanID, BeanDefinition bd){
		this.beanDefinitionMap.put(beanID, bd);
	}

	@Override
    public BeanDefinition getBeanDefinition(String beanID) {
		return this.beanDefinitionMap.get(beanID);
	}

	@Override
    public List<Object> getBeansByType(Class<?> type){
		List<Object> result = new ArrayList<Object>();
		List<String> beanIDs = this.getBeanIDsByType(type);
		for(String beanID : beanIDs){
			result.add(this.getBean(beanID));
		}
		return result;		
	}
	
	private List<String> getBeanIDsByType(Class<?> type){
		List<String> result = new ArrayList<String>();
		for(String beanName :this.beanDefinitionMap.keySet()){
			Class<?> beanClass = null;
			try{
				beanClass = this.getType(beanName);
			}catch(Exception e){
				logger.warn("can't load class for bean :"+beanName+", skip it.");
				continue;
			}
			
			if((beanClass != null) && type.isAssignableFrom(beanClass)){
				result.add(beanName);
			}
		}		
		return result;
	}

	@Override
    public Object getBean(String beanID) {
		BeanDefinition bd = this.getBeanDefinition(beanID);
		if(bd == null){
			return null;
		}
		
		if(bd.isSingleton()){
			Object bean = this.getSingleton(beanID);
			if(bean == null){
				bean = createBean(bd);
				this.registerSingleton(beanID, bean);
			}
			return bean;
		} 
		return createBean(bd);
	}

	@Override
    protected Object createBean(BeanDefinition bd) {
		//创建实例
		Object bean = instantiateBean(bd);
		//设置属性 setter注入
		populateBean(bd, bean);
		bean = initializeBean(bd,bean);
		return bean;
	}

	private Object instantiateBean(BeanDefinition bd) {
		if(bd.hasConstructorArgumentValues()){
			ConstructorResolver resolver = new ConstructorResolver(this);
			return resolver.autowireConstructor(bd);
		}else{
			ClassLoader cl = this.getBeanClassLoader();
			String beanClassName = bd.getBeanClassName();
			try {
				Class<?> clz = cl.loadClass(beanClassName);
				return clz.newInstance();
			} catch (Exception e) {			
				throw new BeanCreationException("create bean for "+ beanClassName +" failed",e);
			}	
		}
	}
	protected void populateBean(BeanDefinition bd, Object bean){
		
		for(BeanPostProcessor processor : this.getBeanPostProcessors()){
			if(processor instanceof InstantiationAwareBeanPostProcessor){
				((InstantiationAwareBeanPostProcessor)processor).postProcessPropertyValues(bean, bd.getID());
			}
		}
		
		List<PropertyValue> pvs = bd.getPropertyValues();

		if (pvs == null || pvs.isEmpty()) {
			return;
		}
		
		BeanDefinitionValueResolver valueResolver = new BeanDefinitionValueResolver(this);
		SimpleTypeConverter converter = new SimpleTypeConverter();
		try{
			for (PropertyValue pv : pvs){
				String propertyName = pv.getName();
				Object originalValue = pv.getValue();
				Object resolvedValue = valueResolver.resolveValueIfNecessary(originalValue);
				
				BeanInfo beanInfo = Introspector.getBeanInfo(bean.getClass());
				PropertyDescriptor[] pds = beanInfo.getPropertyDescriptors();
				for (PropertyDescriptor pd : pds) {
					if(pd.getName().equals(propertyName)){
						Object convertedValue = converter.convertIfNecessary(resolvedValue, pd.getPropertyType());
						pd.getWriteMethod().invoke(bean, convertedValue);
						break;
					}
				}
			}
		}catch(Exception ex){
			throw new BeanCreationException("Failed to obtain BeanInfo for class [" + bd.getBeanClassName() + "]", ex);
		}	
	}

	protected Object initializeBean(BeanDefinition bd, Object bean)  {
		invokeAwareMethods(bean);	
        //Todo，调用Bean的init方法，暂不实现
		if(!bd.isSynthetic()){
			return applyBeanPostProcessorsAfterInitialization(bean,bd.getID());
		}
		return bean;
	}

	public Object applyBeanPostProcessorsAfterInitialization(Object existingBean, String beanName)
			throws BeansException {

		Object result = existingBean;
		for (BeanPostProcessor beanProcessor : getBeanPostProcessors()) {
			result = beanProcessor.afterInitialization(result, beanName);
			if (result == null) {
				return result;
			}
		}
		return result;
	}

	private void invokeAwareMethods(final Object bean) {
		if (bean instanceof BeanFactoryAware) {
			((BeanFactoryAware) bean).setBeanFactory(this);
		}
	}

	@Override
    public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
	}

    @Override
    public ClassLoader getBeanClassLoader() {
		return (this.beanClassLoader != null ? this.beanClassLoader : ClassUtils.getDefaultClassLoader());
	}
    @Override
    public Object resolveDependency(DependencyDescriptor descriptor) {
		
		Class<?> typeToMatch = descriptor.getDependencyType();
		for(BeanDefinition bd: this.beanDefinitionMap.values()){		
			//确保BeanDefinition 有Class对象
			resolveBeanClass(bd);
			Class<?> beanClass = bd.getBeanClass();			
			if(typeToMatch.isAssignableFrom(beanClass)){
				return this.getBean(bd.getID());
			}
		}
		return null;
	}
    public void resolveBeanClass(BeanDefinition bd) {
		if(bd.hasBeanClass()){
			return;
		} else{
			try {
				bd.resolveBeanClass(this.getBeanClassLoader());
			} catch (ClassNotFoundException e) {
				throw new RuntimeException("can't load class:"+bd.getBeanClassName());
			}
		}
	}
    @Override
    public Class<?> getType(String name) throws NoSuchBeanDefinitionException {
		BeanDefinition bd = this.getBeanDefinition(name);
		if(bd == null){
			throw new NoSuchBeanDefinitionException(name);
		}
		resolveBeanClass(bd);		
		return bd.getBeanClass();
	}
}
