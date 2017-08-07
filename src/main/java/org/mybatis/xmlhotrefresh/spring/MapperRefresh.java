package org.mybatis.xmlhotrefresh.spring;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.session.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;

/**
 * Mybatis Fresh Thread
 * Created by sam.pan on 2017/5/10.
 */
public class MapperRefresh implements Runnable{
    private static final Log LOGGER = LogFactory.getLog(MapperRefresh.class);

    @Value("${hotrefresh.enabled}")
    private boolean enabled = true;
    private static boolean refresh = false;
    
    @Value("${hotrefresh.sleepSeconds}")
    private int sleepSeconds = 3;

    private Resource[] mapperLocations;     // Mapper资源路径
    private Configuration configuration;        // MyBatis配置对象

    private Map<String, Long> xmlMap = new HashMap<String, Long>();

    /**
     * 构造函数
     * @param mapperLocations Mapper资源路径
     * @param configuration MyBatis配置对象
     */
    public MapperRefresh(Resource[] mapperLocations, Configuration configuration){
        this.mapperLocations = mapperLocations;
        this.configuration = configuration;
    }

    @Override
    public void run() {
        if(!enabled){
            LOGGER.info("刷新Mapper线程不启动..");
            return;
        }

        //获取每个文件的最后修改时间
        for(Resource mapperLocation : mapperLocations){
            try {
            	LOGGER.info(mapperLocation.getDescription());
            	LOGGER.info(mapperLocation.getFilename());
            	//修复打包成jar包用的时候，资源文件默认不能更新
            	String description = mapperLocation.getDescription();
            	if(StringUtils.startsWith(description, "jar:file")) {
            		continue;
            	}
            	
                final File xmlFile = mapperLocation.getFile();
                final String absolutePath = xmlFile.getAbsolutePath();
                String filePath = absolutePath.replaceAll("\\\\", "/");
                LOGGER.info(filePath);

                xmlMap.put(filePath, xmlFile.lastModified());
            } catch (IOException e) {
            	LOGGER.error(e.getMessage(), e);
            }
        }

        try {
            //程序一开始启动先延迟几秒
            Thread.sleep(3 * 1000);
        } catch (InterruptedException e2) {
            LOGGER.error(e2.getMessage(), e2);
        }

        while (true) {
            try {
                File file = null;
                final Set<Map.Entry<String, Long>> entrySet = xmlMap.entrySet();
                for(Map.Entry<String,Long> entry : entrySet){
                    String filePath = entry.getKey();
                    Long lastModified = entry.getValue();

                    file = new File(filePath);
                    //文件被修改了，刷新
                    if(file.lastModified() > lastModified){
                        refreshXml(file, filePath);
                    }
                }
            } catch (Exception e1) {
                LOGGER.error(e1.getMessage(), e1);
            }
            try {
                Thread.sleep(sleepSeconds * 1000);
            } catch (InterruptedException e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
    }

    //刷新Mybatis的xml
    private void refreshXml(File file, String filePath){
        BufferedInputStream bis = null;
        FileInputStream fis = null;
        try {
            refresh = true;
            fis = new FileInputStream(file);
            bis = new BufferedInputStream(fis);

            // 清理原有资源，更新为自己的StrictMap方便，增量重新加载
            String[] mapFieldNames = new String[]{
                    "mappedStatements", "caches",
                    "resultMaps", "parameterMaps",
                    "keyGenerators", "sqlFragments"
            };
            for (String fieldName : mapFieldNames){
                Field field = configuration.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Map map = ((Map)field.get(configuration));
                if (!(map instanceof StrictMap)){
                    Map newMap = new StrictMap(StringUtils.capitalize(fieldName) + "collection");
                    for (Object key : map.keySet()){
                        try {
                            newMap.put(key, map.get(key));
                        }catch(IllegalArgumentException ex){
                            newMap.put(key, ex.getMessage());
                        }
                    }
                    field.set(configuration, newMap);
                }
            }

            // 清理已加载的资源标识，方便让它重新加载。
            Field loadedResourcesField = configuration.getClass().getDeclaredField("loadedResources");
            loadedResourcesField.setAccessible(true);
            Set loadedResourcesSet = ((Set)loadedResourcesField.get(configuration));
            loadedResourcesSet.remove(filePath);

            //重新编译加载资源文件。
            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(bis, configuration,
                    filePath, configuration.getSqlFragments());
            xmlMapperBuilder.parse();

            //更新修改时间为最新的时间
            xmlMap.put(filePath, file.lastModified());

            LOGGER.info("Refresh file: " + filePath);
        } catch (FileNotFoundException e) {
            LOGGER.error(e.getMessage(), e);
        } catch (IllegalAccessException e) {
            LOGGER.error(e.getMessage(), e);
        } catch (NoSuchFieldException e) {
            LOGGER.error(e.getMessage(), e);
        }finally {
            ErrorContext.instance().reset();
            refresh = false;
            try{
                if(fis != null){
                    fis.close();
                }
            }catch (Exception e){
                LOGGER.error(e.getMessage(), e);
            }

            try{
                if(bis != null){
                    bis.close();
                }
            }catch (Exception e){
                LOGGER.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 重写 org.apache.ibatis.session.StrictMap
     * @param <V>
     */
    protected static class StrictMap<V> extends HashMap<String, V> {

        private static final long serialVersionUID = -4950446264854982944L;
        private final String name;

        public StrictMap(String name, int initialCapacity, float loadFactor) {
            super(initialCapacity, loadFactor);
            this.name = name;
        }

        public StrictMap(String name, int initialCapacity) {
            super(initialCapacity);
            this.name = name;
        }

        public StrictMap(String name) {
            super();
            this.name = name;
        }

        public StrictMap(String name, Map<String, ? extends V> m) {
            super(m);
            this.name = name;
        }

        @SuppressWarnings("unchecked")
        public V put(String key, V value) {
            //如果现在状态为刷新，则刷新(先删除后添加)
            if(MapperRefresh.refresh){
                remove(key);
                MapperRefresh.LOGGER.debug("refresh file:" +key);
            }

            if (containsKey(key)) {
                throw new IllegalArgumentException(name + " already contains value for " + key);
            }
            if (key.contains(".")) {
                final String shortKey = getShortName(key);
                if (super.get(shortKey) == null) {
                    super.put(shortKey, value);
                } else {
                    super.put(shortKey, (V) new Ambiguity(shortKey));
                }
            }
            return super.put(key, value);
        }

        public V get(Object key) {
            V value = super.get(key);
            if (value == null) {
                throw new IllegalArgumentException(name + " does not contain value for " + key);
            }
            if (value instanceof Ambiguity) {
                throw new IllegalArgumentException(((Ambiguity) value).getSubject() + " is ambiguous in " + name
                        + " (try using the full name including the namespace, or rename one of the entries)");
            }
            return value;
        }

        private String getShortName(String key) {
            final String[] keyParts = key.split("\\.");
            return keyParts[keyParts.length - 1];
        }

        protected static class Ambiguity {
            final private String subject;

            public Ambiguity(String subject) {
                this.subject = subject;
            }

            public String getSubject() {
                return subject;
            }
        }
    }
}
