package kkdugi.core.serial.service;

import java.util.ArrayDeque;
import java.util.Date;
import java.util.Queue;
import java.util.stream.LongStream;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import kkdugi.core.serial.SerialConfig;
import kkdugi.core.serial.mapper.SerialMapper;
import kkdugi.core.util.DateUtils;
import kkdugi.core.util.SerialUtils;

@Service
public class SerialService implements InitializingBean {

    private final SerialMapper serialMapper;

    public SerialService(SerialMapper serialMapper) {
        this.serialMapper = serialMapper;
    }

    public Queue<String> nextSerials(SerialConfig config, long size) {
        Queue<String> queue = new ArrayDeque<>();

        Date date = new Date();
        long remaining = size;
        long last = serialMapper.upsertAndGetNext(config.getId(), config.getDateFormat().format(date), remaining);

        while (last > config.getLimit()) {
            // 이번 호출로 seq_val이 last까지 올라갔지만, 실제로 이 구간에 들어가는
            // 건 (last - remaining, limit] 구간뿐이다. limit을 기준으로 시작점을
            // 계산하면(예전 코드의 버그) 이미 다른 호출이 발급한 번호와 겹칠 수
            // 있으므로, 반드시 "이번 호출 전 값"인 last - remaining을 기준으로
            // 잡는다.
            long prev = last - remaining;
            long fitCount = Math.max(0, config.getLimit() - prev);
            if (fitCount > 0) {
                final Date dt = date;
                queue.addAll(LongStream.range(prev, prev + fitCount)
                        .mapToObj(value -> config.format(dt, value + 1))
                        .toList());
            }

            date = getNextDate(config, date);
            // 이번 구간에서 실제로 소진한 만큼만 remaining에서 뺀다.
            // (예전 코드는 queue.size()를 뺐는데, queue는 누적되므로 구간을
            // 3개 이상 걸치는 요청에서 remaining이 과도하게 줄어드는 버그가 있었다.)
            remaining -= fitCount;
            last = serialMapper.upsertAndGetNext(config.getId(), config.getDateFormat().format(date), remaining);
        }

        final Date dt = date;
        long prev = last - remaining;
        queue.addAll(LongStream.range(prev, last)
                .mapToObj(value -> config.format(dt, value + 1))
                .toList());

        return queue;
    }

    private Date getNextDate(SerialConfig config, Date date) {
        return DateUtils.plus(date, config.getDuration());
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        SerialUtils.setSerialService(this);
    }
}
