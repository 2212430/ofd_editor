package com.ofdeditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OfdCacheService {

    // fileId → 原始OFD字节
    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();
    // fileId → 过期时间戳
    private final ConcurrentHashMap<String, Long> expireMap = new ConcurrentHashMap<>();

    // 缓存有效期：2小时
    private static final long TTL_MS = 2 * 60 * 60 * 1000L;

    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor();

    public OfdCacheService() {
        // 每10分钟清理一次过期缓存
        cleaner.scheduleAtFixedRate(this::evict, 10, 10, TimeUnit.MINUTES);
    }

    public void put(String fileId, byte[] ofdBytes) {
        cache.put(fileId, ofdBytes);
        expireMap.put(fileId, System.currentTimeMillis() + TTL_MS);
        log.info("缓存OFD: fileId={}, size={} bytes", fileId, ofdBytes.length);
    }

    public byte[] get(String fileId) {
        Long exp = expireMap.get(fileId);
        if (exp == null || System.currentTimeMillis() > exp) {
            cache.remove(fileId);
            expireMap.remove(fileId);
            return null;
        }
        return cache.get(fileId);
    }

    private void evict() {
        long now = System.currentTimeMillis();
        expireMap.forEach((id, exp) -> {
            if (now > exp) {
                cache.remove(id);
                expireMap.remove(id);
                log.debug("清理过期缓存: fileId={}", id);
            }
        });
    }
}