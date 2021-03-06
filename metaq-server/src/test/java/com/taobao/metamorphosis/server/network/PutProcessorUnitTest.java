package com.taobao.metamorphosis.server.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.junit.Before;
import org.junit.Test;

import com.taobao.gecko.core.command.ResponseCommand;
import com.taobao.metamorphosis.network.BooleanCommand;
import com.taobao.metamorphosis.network.HttpStatus;
import com.taobao.metamorphosis.network.PutCommand;
import com.taobao.metamorphosis.server.assembly.BrokerCommandProcessor.StoreAppendCallback;
import com.taobao.metamorphosis.server.store.Location;
import com.taobao.metamorphosis.server.store.MessageStore;
import com.taobao.metamorphosis.utils.MessageFlagUtils;


public class PutProcessorUnitTest extends BaseProcessorUnitTest {
    private PutProcessor processor;
    final String topic = "PutProcessorUnitTest";


    // private SessionContext sessionContext;

    @Before
    public void setUp() {
        this.mock();

        this.processor = new PutProcessor(this.commandProcessor, null);
    }


    @Test
    public void testProcessRequestNormal() throws Exception {
        final int partition = 1;
        final int opaque = -1;
        final long offset = 1024L;
        final byte[] data = new byte[1024];
        final int flag = MessageFlagUtils.getFlag(null);
        final PutCommand request = new PutCommand(this.topic, partition, data, null, flag, opaque);
        final long msgId = 100000L;
        final MessageStore store = this.mocksControl.createMock(MessageStore.class);
        EasyMock.expect(this.idWorker.nextId()).andReturn(msgId);
        EasyMock.expect(this.storeManager.getOrCreateMessageStore(this.topic, partition)).andReturn(store);
        final BooleanCommand expectResp =
                new BooleanCommand(opaque, HttpStatus.Success, msgId + " " + partition + " " + offset);
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final PutCallback cb = new PutCallback() {

            @Override
            public void putComplete(final ResponseCommand resp) {
                invoked.set(true);
                if (!expectResp.equals(resp)) {
                    throw new RuntimeException();
                }
            }
        };
        store.append(msgId, request,
            this.commandProcessor.new StoreAppendCallback(partition, this.metaConfig.getBrokerId() + "-" + partition,
                request, msgId, cb));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {

            @Override
            public Object answer() throws Throwable {
                ((StoreAppendCallback) EasyMock.getCurrentArguments()[2]).appendComplete(new Location(offset, 1024));
                return null;
            }

        });
        this.brokerZooKeeper.registerTopicInZk(this.topic);
        EasyMock.expectLastCall();
        this.mocksControl.replay();
        this.commandProcessor.processPutCommand(request, this.sessionContext, cb);
        this.mocksControl.verify();
        assertEquals(0, this.statsManager.getCmdPutFailed());
        assertTrue(invoked.get());
    }


    @Test
    public void testProcessRequestAppendFail() throws Exception {
        final int partition = 1;
        final int opaque = -1;
        final byte[] data = new byte[1024];
        final int flag = MessageFlagUtils.getFlag(null);
        final PutCommand request = new PutCommand(this.topic, partition, data, null, flag, opaque);
        final long msgId = 100000L;
        final MessageStore store = this.mocksControl.createMock(MessageStore.class);
        EasyMock.expect(this.idWorker.nextId()).andReturn(msgId);
        EasyMock.expect(this.storeManager.getOrCreateMessageStore(this.topic, partition)).andReturn(store);
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final BooleanCommand expectResp =
                new BooleanCommand(request.getOpaque(), HttpStatus.InternalServerError, "put message failed");
        final PutCallback cb = new PutCallback() {

            @Override
            public void putComplete(final ResponseCommand resp) {
                invoked.set(true);
                if (!expectResp.equals(resp)) {
                    throw new RuntimeException();
                }
            }
        };
        store.append(msgId, request,
            this.commandProcessor.new StoreAppendCallback(partition, this.metaConfig.getBrokerId() + "-" + partition,
                request, msgId, cb));
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {

            @Override
            public Object answer() throws Throwable {
                ((StoreAppendCallback) EasyMock.getCurrentArguments()[2]).appendComplete(Location.InvalidLocaltion);
                return null;
            }

        });
        this.brokerZooKeeper.registerTopicInZk(this.topic);
        EasyMock.expectLastCall();
        this.mocksControl.replay();
        this.commandProcessor.processPutCommand(request, this.sessionContext, cb);
        this.mocksControl.verify();
        assertEquals(1, this.statsManager.getCmdPutFailed());
        assertTrue(invoked.get());

    }


    @Test
    public void testProcessRequestPartitionClosed() throws Exception {
        final int partition = 1;
        final int opaque = -1;
        final byte[] data = new byte[1024];
        final int flag = MessageFlagUtils.getFlag(null);
        final PutCommand request = new PutCommand(this.topic, partition, data, null, flag, opaque);
        this.metaConfig.setTopics(Arrays.asList(this.topic));
        this.metaConfig.closePartitions(this.topic, partition, partition);
        final BooleanCommand expectedResp =
                new BooleanCommand(request.getOpaque(), HttpStatus.Forbidden, "Partition["
                        + this.metaConfig.getBrokerId() + "-" + request.getPartition() + "] has been closed");
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final PutCallback cb = new PutCallback() {

            @Override
            public void putComplete(final ResponseCommand resp) {
                invoked.set(true);
                if (!expectedResp.equals(resp)) {
                    throw new RuntimeException();
                }
            }
        };
        this.mocksControl.replay();
        this.commandProcessor.processPutCommand(request, this.sessionContext, cb);

        this.mocksControl.verify();

    }
}
