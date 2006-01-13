package com.opensymphony.oscache.core;

import java.text.ParseException;
import java.util.Properties;

import com.opensymphony.oscache.algorithm.UnlimitedEvictionAlgorithm;
import com.opensymphony.oscache.events.CacheMapAccessEventType;
import com.opensymphony.oscache.util.FastCronParser;

/**
 * A base class that provides most of the core caching functionality for a
 * concrete cache implementation.
 */
public abstract class BaseCache implements Cache {

	private EvictionAlgorithm policy;

	private String name;

	/**
	 * Initialises the base cache. Valid properties are:
	 * <ul>
	 * <li>{@link #MIN_THREADS_PARAM} - the minimun number of threads to pool</li>
	 * <li>{@link #MAX_THREADS_PARAM} - the maximum number of threads to pool</li>
	 * </ul>
	 * 
	 * @param props
	 *            any configuration parameters that the cache requires.
	 * @param policy
	 *            the eviction policy for the cache to use. If <code>null</code>
	 *            is specified, the cache will default to using the
	 *            {@link UnlimitedEvictionAlgorithm}.
	 */
	public void init(Properties props, EvictionAlgorithm policy) {
		if (policy == null)
			this.policy = new UnlimitedEvictionAlgorithm();
		else {
			this.policy = policy;
		}

		init(props);
	}

	/**
	 * Passes though the configuration parameters to a concrete implentation so
	 * it can perform any additional configuration that might be necessary.
	 * 
	 * @param props
	 *            the cache configuration parameters.
	 */
	protected abstract void init(Properties props);

	/**
	 * Clears the entire cache. This will result in a
	 * {@link CacheEvent#CLEAR_EVENT} being fired even if the cache already
	 * contained no entries.
	 */
	public synchronized void clear() {
		clearInternal();
	}

	/**
	 * Shuts down the cache by waiting for any asynchronous cache loaders to
	 * complete. Depending on the loader that is in use and how much load it is
	 * under, this operation may take a long time to complete.
	 */
	public void shutdown() {

	}
	
	/**
	 * Retrieves an object from the cache.
	 * 
	 * @param key
	 *            the key of the object to retrieve.
	 * @return the cached object, or <code>null</code> if the object could not
	 *         be found and could not be loaded.
	 */
	public synchronized Object get(Object key) {
		return get(key, 0, null);
	}

	/**
	 * Retrieves an object from the cache.
	 * 
	 * @param key
	 *            the key of the object to retrieve.
	 * @return the cached object, or <code>null</code> if the object could not
	 *         be found and could not be loaded.
	 */
	public synchronized Object get(Object key, int refreshPeriod, String cronExpiry) {
		CacheEntry cacheEntry = getEntry(key);

		Object content = cacheEntry.getValue();
		CacheMapAccessEventType accessEventType = CacheMapAccessEventType.HIT;
		// Check if this entry has expired or has not yet been added to the
		// cache. If
		// so, we need to decide whether to block or serve stale content
		if (this.isStale(cacheEntry, refreshPeriod, cronExpiry)) {
			remove(key);
			return null;
		} else {
			policy.get(key, cacheEntry);
			return content;
		}

		
	}

	public synchronized Object remove(Object key) {
		CacheEntry result = removeInternal(key);
		if (result != null) {
			policy.remove(key, result);
		}
		return result;
	}

	/**
	 * Store the supplied entry in the cache.
	 * 
	 * @param key
	 *            the key to store the entry under.
	 * @param value
	 *            the object to store.
	 * @return the previous object that was stored under this key, if any.
	 */
	public synchronized Object put(Object key, Object value) {

		CacheEntry result = putInternal(new CacheEntry(key, value));
		policy.put(key, value);

		// Remove an entry from the cache if the eviction policy says we need to
		Object evictionKey = policy.evict();
		if (evictionKey != null) {
			removeInternal(evictionKey);
		}

		return result.getValue();
	}

	/**
	 * Get an entry from this cache or create one if it doesn't exist.
	 * 
	 * @param key
	 *            The key of the cache entry
	 * @return CacheEntry for the specified key.
	 */
	public synchronized CacheEntry getEntry(Object key) {
		return getEntry(key, null, null);
	}
	/**
	 * Get an entry from this cache or create one if it doesn't exist.
	 * 
	 * @param key
	 *            The key of the cache entry
	 * @param policy
	 *            Object that implements refresh policy logic
	 * @param origin
	 *            The origin of request (optional)
	 * @return CacheEntry for the specified key.
	 */
	public synchronized CacheEntry getEntry(Object key, EntryRefreshPolicy policy,
			String origin) {
		CacheEntry cacheEntry = getInternal(key);


		return cacheEntry;
	}

	/**
	 * Indicates whether or not the cache entry is stale.
	 * 
	 * @param cacheEntry
	 *            The cache entry to test the freshness of.
	 * @param refreshPeriod
	 *            The maximum allowable age of the entry, in seconds.
	 * @param cronExpiry
	 *            A cron expression specifying absolute date(s) and/or time(s)
	 *            that the cache entry should expire at. If the cache entry was
	 *            refreshed prior to the most recent match for the cron
	 *            expression, the entry will be considered stale.
	 * 
	 * @return <code>true</code> if the entry is stale, <code>false</code>
	 *         otherwise.
	 */
	protected boolean isStale(CacheEntry cacheEntry, int refreshPeriod,
			String cronExpiry) {
		boolean result = cacheEntry.needsRefresh(refreshPeriod);

		if ((cronExpiry != null) && (cronExpiry.length() > 0)) {
			try {
				FastCronParser parser = new FastCronParser(cronExpiry);
				result = result
						|| parser
								.hasMoreRecentMatch(cacheEntry.getLastUpdate());
			} catch (ParseException e) {
			}
		}

		return result;
	}

	/**
	 * Retrieves the name of this cache instance.
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the name of this cache instance to the specified value.
	 * 
	 * @param name
	 *            the new name for the cache.
	 */
	public void setName(String name) {
		this.name = name;
	}

	public void setEvictionPolicy(EvictionAlgorithm policy, Properties props)
			throws IllegalStateException {
	}

	/**
	 * Retrieves the cache entry from the underlying datastore. <p/> The
	 * implementation of this method does not need to be synchronized; the
	 * synchronization is already managed by the calling method.
	 * 
	 * @param key
	 *            the key that indentifies the cache entry to retrieve.
	 * @return the cache entry, or <code>null</code> if no cache entry was
	 *         found.
	 */
	protected abstract CacheEntry getInternal(Object key);

	/**
	 * Retrieves the cache entry from the underlying datastore. <p/> The
	 * implementation of this method does not need to be synchronized; the
	 * synchronization is already managed by the calling method.
	 * 
	 * @param key
	 *            the key that indentifies the cache entry to retrieve.
	 * @return the existing cache entry, or <code>null</code> if no cache
	 *         entry was found.
	 */
	protected abstract CacheEntry putInternal(CacheEntry entry);

	/**
	 * Removes a cache entry from the underlying datastore. <p/> The
	 * implementation of this method does not need to be synchronized; the
	 * synchronization is already managed by the calling method.
	 * 
	 * @param key
	 *            the key that indentifies the cache entry to remove.
	 * @return the cache entry that was removed, or <code>null</code> if no
	 *         cache entry was found with that key.
	 */
	protected abstract CacheEntry removeInternal(Object key);

	/**
	 * Cleans out the entire cache. <p/> The implementation of this method does
	 * not need to be synchronized; the synchronization is already managed by
	 * the calling method.
	 */
	protected abstract void clearInternal();

}