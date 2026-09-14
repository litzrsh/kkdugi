package kkdugi.core.serial.service;

import java.util.ArrayDeque;
import java.util.Date;
import java.util.Queue;
import java.util.stream.LongStream;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kkdugi.core.serial.SerialGenerator;
import kkdugi.core.serial.mapper.SerialMapper;
import kkdugi.core.util.SerialUtils;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SerialService implements InitializingBean {

    private final SerialMapper serialMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_UNCOMMITTED, noRollbackFor = Throwable.class)
    public Queue<String> getSerials(SerialGenerator gen, long size) {
        if (size < 1 || size > gen.max()) {
            throw new IllegalArgumentException("size must be between 1 and " + gen.max());
        }

        final Date date = new Date();
        String key = gen.getDateFormat().format(date);

        ArrayDeque<String> queue = new ArrayDeque<>();
        long last = serialMapper.getSerial(gen.getId(), key, size);
        queue.addAll(LongStream.range(last - size, Math.min(gen.max(), last))
                .mapToObj(value -> gen.format(date, value))
                .toList());
        size = last - gen.max();
        while ((size = last - gen.max()) > 0) {
            key = nextKey(key);
            last = serialMapper.getSerial(gen.getId(), key, size);
            final String text = key;
            queue.addAll(LongStream.range(last - size, Math.min(gen.max(), last))
                    .mapToObj(value -> gen.format(text, value))
                    .toList());
        }

        return queue;
    }

    private static String nextKey(String key) {
        String next = Long.toString(Long.parseLong(key) + 1);
        return next.length() < key.length() ? "0".repeat(key.length() - next.length()) + next : next;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        SerialUtils.setSerialService(this);
    }
}
