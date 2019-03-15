package MultiThreadSellTicket;
import org.springframework.util.StopWatch;
import redis.clients.jedis.Jedis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 使用redis
 *  setnx  getset 方式 实现 分布式锁
 *
 */
public class TicketRunnable implements Runnable {
    //利用它可以实现类似计数器的功能。比如有一个任务A，它要等待其他4个任务执行完毕之后才能执行，此时就可以利用CountDownLatch来实现这种功能了。
    private CountDownLatch count;
    //通过它可以实现让一组线程等待至某个状态之后再全部同时执行。叫做回环是因为当所有等待线程都被释放以后，CyclicBarrier可以被重用。
    private CyclicBarrier barrier;
    private static final Integer Lock_Timeout = 10000;
    private static final String lockKey = "LockKey";
    private volatile boolean  working = true;

    public TicketRunnable(CountDownLatch count,CyclicBarrier barrier) {
        this.count = count;
        this.barrier = barrier;
    }

    private int num = 20;  // 总票数（多线程并发卖票）

    public void sellTicket(Jedis jedis) {
        try{
            //该方法是原子的，如果key不存在，则设置当前key成功，返回1；如果当前key已经存在，则设置当前key失败，返回0。
            boolean getLock = tryLock(jedis,lockKey, Long.valueOf(10));
            if(getLock){
                //获得了锁开始执行
                // Do your job
                if (num > 0) {
                    System.out.print("=============="+Thread.currentThread().getName()+"===============  售出票号" + num);
                    num--;
                    if(num!=0){
                        System.out.println(",还剩" + num + "张票--" );
                    } else {
                        System.out.println("，票已经票完!--");
                        working = false;
                    }
                }
            }
        }catch(Exception e){
            System.out.println(e);
        }finally {
            try {
                realseLock(jedis, lockKey);
                Thread.sleep(600);
            }catch (Exception e ) {
                e.printStackTrace();
            }
        }

    }

    /**
     * 获取锁
     * @param jedis
     * @param lockKey
     * @param timeout
     * @return
     */
    public boolean tryLock(Jedis jedis,String lockKey, Long timeout) {
        try {
            Long currentTime = System.currentTimeMillis();//开始加锁的时间
            boolean result = false;

            while (true && working) {
                if ((System.currentTimeMillis() - currentTime) / 1000 > timeout) {//当前时间超过了设定的超时时间，竞争锁超时
                    System.out.println("---------------- try lock time out.");
                    break;
                } else {
                    result = innerTryLock(jedis,lockKey);
                    if (result) {
                        System.out.println("=============="+Thread.currentThread().getName()+"===============  获取到锁,开始工作！");
                        break;
                    } else {
                        System.out.println(Thread.currentThread().getName()+" Try to get the Lock,and wait 200 millisecond....");
                        Thread.sleep(200);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 释放锁
     * @param jedis
     * @param lockKey
     */
    public void realseLock(Jedis jedis,String lockKey) {
        if (!checkIfLockTimeout(jedis,System.currentTimeMillis(), lockKey)) {
            jedis.del(lockKey);
            System.out.println("=============="+Thread.currentThread().getName()+"===============  释放锁！");
        }
    }

    /**
     * 获取锁具体实现
     * @param jedis
     * @param lockKey
     * @return
     */
    private boolean innerTryLock(Jedis jedis,String lockKey) {
        long currentTime = System.currentTimeMillis();//当前时间
        String lockTimeDuration = String.valueOf(currentTime + Lock_Timeout + 1);//锁的持续时间
        Long result = jedis.setnx(lockKey, lockTimeDuration);

        if (result == 1) {  //返回1 代表第1次设置
            return true;
        } else {
            if (checkIfLockTimeout(jedis,currentTime, lockKey)) {
                String preLockTimeDuration = jedis.getSet(lockKey, lockTimeDuration);  //此处需要再判断一次
                if(preLockTimeDuration == null){  //如果 返回值 为空， 代表获取到锁 否则 锁被其他线程捷足先登
                    return true;
                }else{
                    if (currentTime > Long.parseLong(preLockTimeDuration)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /**
     *
     * @param jedis
     * @param currentTime
     * @param lockKey
     * @return
     */
    private boolean checkIfLockTimeout(Jedis jedis,Long currentTime, String lockKey) {
        String value = jedis.get(lockKey);
        if (value == null) {
            return true;
        }else{
            if (currentTime > Long.parseLong(value)) {//当前时间超过锁的持续时间
                return true;
            } else {
                return false;
            }
        }

    }



    @Override
    public void run() {
        System.out.println(Thread.currentThread().getName()+"到达,等待中...");
        Jedis jedis = new Jedis("localhost", 6379);

        try{
            barrier.await();    // 此处阻塞  等所有线程都到位后一起进行抢票
            if(Thread.currentThread().getName().equals("pool-1-thread-1")){
                System.out.println("---------------全部线程准备就绪,开始抢票----------------");
            }else {
                Thread.sleep(5);
            }
            while (num > 0) {
                sellTicket(jedis);
            }
            count.countDown();  //当前线程结束后，计数器-1
        }catch (Exception e){e.printStackTrace();}


    }

    /**
     *
     * @param args
     */
    public static void main(String[] args) {
        int threadNum = 5;    //模拟多个窗口 进行售票(启动几个线程去竞争锁)
        final CyclicBarrier barrier = new CyclicBarrier(threadNum);
        final CountDownLatch count = new CountDownLatch(threadNum);  // 用于统计 执行时长
        //需要监控不同方法的运行时间，知道性能瓶颈,利用springframework框架的工具类StopWatch可以快速方便的查看到每段代码运行的时间，准确确定性能瓶颈所在
        StopWatch watch = new StopWatch();
        watch.start();
        TicketRunnable tickets = new TicketRunnable(count,barrier);
        //创建一个线程池
        ExecutorService executorService = Executors.newFixedThreadPool(threadNum);
        //ExecutorService executorService = Executors.newCachedThreadPool();
        for (int i = 0; i < threadNum; i++) {   //此处 设置数值  受限于 线程池中的数量
            executorService.submit(tickets);
        }
        try {
            count.await();
            executorService.shutdown();
            watch.stop();
            System.out.println("耗 时:" + watch.getTotalTimeSeconds() + "秒");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}