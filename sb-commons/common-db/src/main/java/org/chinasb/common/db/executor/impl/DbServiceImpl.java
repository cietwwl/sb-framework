package org.chinasb.common.db.executor.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.PostConstruct;

import org.chinasb.common.Constants;
import org.chinasb.common.NamedThreadFactory;
import org.chinasb.common.db.dao.CommonDao;
import org.chinasb.common.db.executor.DbCallback;
import org.chinasb.common.db.executor.DbService;
import org.chinasb.common.db.model.BaseModel;
import org.chinasb.common.utility.CollectionUtility;
import org.chinasb.common.utility.ThreadPoolUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Service;

import com.google.common.collect.Sets;

/**
 * 数据库持久化服务
 * @author zhujuan
 */
@Service
public class DbServiceImpl implements DbService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DbServiceImpl.class);
    /**
     * 线程池
     */
    private static ExecutorService DB_POOL_SERVICE;
    /**
     * 任务队列
     */
    private static final LinkedBlockingQueue<Runnable> DB_SAVER_QUEUE = new LinkedBlockingQueue<Runnable>(Integer.MAX_VALUE);
    /**
     * 实体集合
     */
    private static final ConcurrentMap<Class<?>, Set<BaseModel<?>>> DB_OBJECT_MAP = new ConcurrentHashMap<Class<?>, Set<BaseModel<?>>>(100);
    /**
     * 允许尝试提交次数
     */
    private static final int MAX_RETRY_COUNT = 5;
    
    /**
     * 线程池维护线程的最少数量
     */
    @Autowired(required = false)
    @Qualifier("dbcache.db_pool_capacity")
    private Integer dbPoolSize;
    
    /**
     * 线程池维护线程的最大数量
     */
    @Autowired(required = false)
    @Qualifier("dbcache.db_pool_max_capacity")
    private Integer dbPoolMaxSize;
    
    /**
     * 线程池维护线程所允许的空闲时间
     */
    @Autowired(required = false)
    @Qualifier("dbcache.db_pool_keep_alive_time")
    private Integer keepAliveTime;
    
    /**
     * （实时任务：entityBlockTime <= 0，周期性任务：entityBlockTime > 0）
     */
    @Autowired(required = false)
    @Qualifier("dbcache.max_block_time_of_entity_cache")
    private Integer entityBlockTime;
    
    @Autowired
    @Qualifier("commonDaoImpl")
    private CommonDao commonDao;
    private final ReentrantLock takeLock;
    private final Condition notEmpty;
    /**
     * 实体队列持久化处理任务
     */
    public final Runnable CUSTOMER_TASK;
    /**
     * 周期性处理提交实体任务
     */
    public final Runnable HANDLER_CACHED_OBJ_TASK;

    public DbServiceImpl() {
        dbPoolSize = Integer.valueOf(DEFAULT_DB_THREADS);
        dbPoolMaxSize = Integer.valueOf(DEFAULT_DB_THREADS * 2);
        keepAliveTime = Integer.valueOf(Constants.ONE_MINUTE_SECOND * 10);
        entityBlockTime = Integer.valueOf(Constants.ONE_MINUTE_MILLISECOND);
        takeLock = new ReentrantLock();
        notEmpty = takeLock.newCondition();
        CUSTOMER_TASK = new Runnable() {
            public void run() {
                try {
                    for (;;) {
                        Runnable task = DB_SAVER_QUEUE.take();
                        if (task != null) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("submit task.. QUEUE_SIZE:[{}] ",
                                        Integer.valueOf(DB_SAVER_QUEUE.size()));
                            }
                            DB_POOL_SERVICE.submit(task);
                        }
                    }
                } catch (Exception ex) {
                    LOGGER.error("Error: {}", ex);
                }
            }
        };
        HANDLER_CACHED_OBJ_TASK = new Runnable() {
            public void run() {
                try {
                    for (;;) {
                        if (entityBlockTime.intValue() > 0) {
                            takeLock.lockInterruptibly();
                            try {
                                notEmpty.await(entityBlockTime.intValue(), TimeUnit.MILLISECONDS);
                            } finally {
                                takeLock.unlock();
                            }
                        }
                        submitCachedDbObject();
                    }
                } catch (Exception ex) {
                    LOGGER.error("Error: " + ex.getMessage());
                }
            }
        };
    }
    
    /**
     * 初始化
     */
    @PostConstruct
    void initialize() {
        NamedThreadFactory threadFactory = new NamedThreadFactory("缓存模块:入库线程池");
        DB_POOL_SERVICE =
                new ThreadPoolExecutor(dbPoolSize.intValue(), dbPoolMaxSize.intValue(),
                        keepAliveTime.intValue(), TimeUnit.SECONDS,
                        new LinkedBlockingQueue<Runnable>(), threadFactory);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Initialize DB Daemon Thread...");
        }
        NamedThreadFactory factory = new NamedThreadFactory("数据库入库Daemon线程", true);
        Thread thread = factory.newThread(CUSTOMER_TASK);
        thread.start();

        Thread pollCachedObjThread = factory.newThread(HANDLER_CACHED_OBJ_TASK);
        pollCachedObjThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownHookRunnable()));
    }
    
    /**
     * 服务停止
     */
    private class ShutdownHookRunnable implements Runnable {
        private ShutdownHookRunnable() {}

        public void run() {
            workDone();
        }
    }
    

    /**
     * 创建实体缓存持久化任务
     * @param callback
     * @param entityCache
     * @return
     */
    private Runnable createTask(final DbCallback callback, final EntityCache entityCache) {
        return new Runnable() {
            public void run() {
                handleTask(callback, entityCache, false);
            }
        };
    }
    
    /**
     * 提交实体缓存持久化任务
     * @param callback
     * @param entityCache
     */
    private void add2Queue(DbCallback callback, EntityCache entityCache) {
        DB_SAVER_QUEUE.add(createTask(null, entityCache));
    }
    
    /**
     * 处理实体缓存持久化任务
     * @param callback 回调
     * @param entityCache 实体对象缓存数据
     * @param removeFromSubmitCache
     */
    private void handleTask(DbCallback callback, EntityCache entityCache,
            boolean removeFromSubmitCache) {
        Collection<BaseModel<?>> entities = entityCache.getEntities();
        if ((entities != null) && (!entities.isEmpty())) {
            try {
                for (BaseModel<?> entity : entities) {
                    Set<BaseModel<?>> entitySet = DB_OBJECT_MAP.get(entity.getClass());
                    if ((entitySet != null) && (!entitySet.isEmpty())) {
                        entitySet.remove(entity);
                    }
                }
                commonDao.update(entities);
            } catch (Exception ex) {
                LOGGER.error("执行入库时产生异常", ex);
                if (entityCache.getRetryCount() < MAX_RETRY_COUNT) {
                    add2Queue(callback, entityCache);
                }
                return;
            }
        }
        if (callback != null) {
            try {
                callback.doAfter();
            } catch (Exception ex) {
                LOGGER.error("执行入库后回调时产生异常", ex);
            }
        }
    }
    
    /**
     * 提交实体持久化任务异步处理
     */
    private final void submitCachedDbObject() {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("submitCachedDbObject [size[{}]]", DB_OBJECT_MAP.size());
        }
        if (DB_OBJECT_MAP.isEmpty()) {
            return;
        }
        ConcurrentMap<Class<?>, Set<BaseModel<?>>> objSet = new ConcurrentHashMap<Class<?>, Set<BaseModel<?>>>();
        synchronized (DB_OBJECT_MAP) {
            objSet.putAll(DB_OBJECT_MAP);
            DB_OBJECT_MAP.clear();
        }
        Iterator<Class<?>> iterator = objSet.keySet().iterator();
        while (iterator.hasNext()) {
            Object key = iterator.next();
            Set<BaseModel<?>> set = objSet.get(key);
            if ((set != null) && (set.size() > 0)) {
                DB_SAVER_QUEUE.add(createTask(null, new EntityCache(new Object[] {set})));
            }
        }
    }

    /**
     * 提交实体持久化任务实时处理
     */
    private final void updateIntimeCachedDbObject() {
        if (DB_OBJECT_MAP.isEmpty()) {
            return;
        }
        ConcurrentMap<Class<?>, Set<BaseModel<?>>> objSet = new ConcurrentHashMap<Class<?>, Set<BaseModel<?>>>();
        synchronized (DB_OBJECT_MAP) {
            objSet.putAll(DB_OBJECT_MAP);
            DB_OBJECT_MAP.clear();
        }
        int subCount = 100;
        for (Set<BaseModel<?>> concurrentHashSet : objSet.values()) {
            if ((concurrentHashSet != null) && (!concurrentHashSet.isEmpty())) {
                int maxSize = concurrentHashSet.size();
                List<BaseModel<?>> list = new ArrayList<BaseModel<?>>(concurrentHashSet);
                int maxCount =
                        maxSize % subCount == 0 ? maxSize / subCount : maxSize / subCount + 1;
                for (int index = 0; index < maxCount; index++) {
                    List<BaseModel<?>> subList =
                            CollectionUtility.subListCopy(list, subCount * index, subCount);
                    updateEntityIntime(new Object[] {subList});
                }
            }
        }
    }
    
    /**
     * 添加实体到持久化集合
     * @param entities
     */
    private void put2ObjectMap(Collection<BaseModel<?>> entities) {
        for (BaseModel<?> e : entities) {
            put2ObjectMap(e);
        }
    }

    /**
     * 添加实体到持久化集合
     * @param entity
     */
    private void put2ObjectMap(BaseModel<?> entity) {
        Class<?> clazz = entity.getClass();
        Set<BaseModel<?>> objs = DB_OBJECT_MAP.get(clazz);
        if (objs == null) {
            Set<BaseModel<?>> concurrentHashSet = Sets.newConcurrentHashSet();
            DB_OBJECT_MAP.putIfAbsent(clazz, concurrentHashSet);
            objs = DB_OBJECT_MAP.get(clazz);
        }
        objs.add(entity);
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <T> void submitUpdate2Queue(T... entities) {
        if (entities.length <= 0) {
            return;
        }
        if (entityBlockTime.intValue() <= 0) {
            add2Queue(null, new EntityCache(entities));
            return;
        }
        Collection<BaseModel<?>> baseModels = getBaseModelList(entities);
        put2ObjectMap(baseModels);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void updateEntityIntime(T... entities) {
        if (entities.length > 0) {
            handleTask(null, new EntityCache(entities), true);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void updateEntityIntime(DbCallback callback, T... entities) {
        handleTask(callback, new EntityCache(entities), true);
    }

    @Override
    public <T> boolean isInDbQueue(T entity) {
        if ((entity != null) && ((entity instanceof BaseModel))) {
            Set<BaseModel<?>> entitySet = DB_OBJECT_MAP.get(entity.getClass());
            if ((entitySet != null) && (!entitySet.isEmpty())) {
                return entitySet.contains(entity);
            }
        }
        return false;
    }
    
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        submitCachedDbObject();
        workDone();
    }

    /**
     * 服务停止处理
     */
    public void workDone() {
        while (DB_POOL_SERVICE != null) {
            if (DB_POOL_SERVICE.isShutdown()) {
                break;
            }
            if (!DB_OBJECT_MAP.isEmpty()) {
                updateIntimeCachedDbObject();
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                LOGGER.error("{}", e);
            }
            if (DB_SAVER_QUEUE.isEmpty()) {
                ThreadPoolUtils.shutdownGraceful(DB_POOL_SERVICE,
                        Constants.ONE_MINUTE_MILLISECOND * 5);
            }
        }
    }
    
    /**
     * 实体缓存
     */
    public static class EntityCache {
        /**
         * 实体集合
         */
        private Collection<BaseModel<?>> entities;
        /**
         * 尝试次数
         */
        private int retryCount;

        /**
         * 获取实体集合
         * @return
         */
        public Collection<BaseModel<?>> getEntities() {
            return entities;
        }

        /**
         * 获取尝试次数
         * @return
         */
        public int getRetryCount() {
            return retryCount;
        }

        public EntityCache(Object... entities) {
            this.entities = getBaseModelList(entities);
        }

        public EntityCache(Collection<Object> entities) {
            this.entities = getBaseModelList(new Object[] {entities});
        }
    }
    

    /**
     * 获取可持久化的实体集合
     * @param entities 
     * @return
     */
    @SuppressWarnings("unchecked")
    private static Collection<BaseModel<?>> getBaseModelList(Object... entities) {
        List<BaseModel<?>> baseModes = new LinkedList<BaseModel<?>>();
        for (Object entity : entities) {
            if ((entity instanceof BaseModel)) {
                baseModes.add((BaseModel<?>) entity);
            } else if ((entity instanceof Collection)) {
                Collection<Object> list = (Collection<Object>) entity;
                for (Object value : list) {
                    if ((value instanceof BaseModel)) {
                        baseModes.add((BaseModel<?>) value);
                    } else {
                        baseModes.addAll(getBaseModelList(new Object[] {value}));
                    }
                }
            } else if ((entity instanceof Object[])) {
                Object[] array = (Object[]) entity;
                for (Object value : array) {
                    if ((value instanceof BaseModel)) {
                        baseModes.add((BaseModel<?>) value);
                    } else {
                        baseModes.addAll(getBaseModelList(new Object[] {value}));
                    }
                }
            } else {
                LOGGER.error("对象没有被保存. class:{} value:{}", entity.getClass(), entity);
            }
        }
        return baseModes;
    }
}
