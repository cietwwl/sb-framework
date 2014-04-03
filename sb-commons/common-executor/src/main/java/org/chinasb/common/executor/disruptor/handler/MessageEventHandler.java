package org.chinasb.common.executor.disruptor.handler;

import org.chinasb.common.executor.disruptor.event.MessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.WorkHandler;

/**
 * 消息处理器抽象
 * @author zhujuan
 */
public class MessageEventHandler implements EventHandler<MessageEvent>, WorkHandler<MessageEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(MessageEventHandler.class);

    @Override
    public void onEvent(final MessageEvent event, final long sequence, final boolean endOfBatch)
            throws Exception {
        try {
            event.task.run();
        } catch (Exception e) {
            LOG.error("EXECUTE TASK ERROR");
        }
    }

    @Override
    public void onEvent(MessageEvent event) throws Exception {
        try {
            event.task.run();
        } catch (Exception e) {
            LOG.error("EXECUTE TASK ERROR");
        }
    }
}
