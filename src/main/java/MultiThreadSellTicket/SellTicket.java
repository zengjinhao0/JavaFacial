package MultiThreadSellTicket;

import org.springframework.util.StopWatch;
import redis.clients.jedis.Jedis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SellTicket implements Runnable {

    //利用它可以实现类似计数器的功能。比如有一个任务A，它要等待其他4个任务执行完毕之后才能执行，此时就可以利用CountDownLatch来实现这种功能了。
    private CountDownLatch count;
    //通过它可以实现让一组线程等待至某个状态之后再全部同时执行。叫做回环是因为当所有等待线程都被释放以后，CyclicBarrier可以被重用。
    private CyclicBarrier barrier;
    private static final Integer Lock_Timeout = 10000;
    private static final String lockKey = "LockKey";
    private volatile boolean  working = true;

    public SellTicket(CountDownLatch count,CyclicBarrier barrier) {
        this.count = count;
        this.barrier = barrier;
    }

    private int num = 100;  // 所有线程共享，总票数（多线程并发卖票）


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
            while (num > 0) {//有票就继续抢票
                sellTicket(jedis);
            }
            count.countDown();  //当前线程结束后，计数器-1
        }catch (Exception e){e.printStackTrace();}

    }


    public void sellTicket(Jedis jedis) {
        try{
            //该方法是原子的，如果key不存在，则设置当前key成功，返回1；如果当前key已经存在，则设置当前key失败，返回0。
            //只有获取的锁的才允许减少票数
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
            try {//当处理完毕后，比较自己的处理时间和对于锁设置的超时时间，如果小于锁设置的超时时间，则直接执行delete释放锁；如果大于锁设置的超时时间，则不需要再锁进行处理。
                realseLock(jedis, lockKey);
                Thread.sleep(600);
            }catch (Exception e ) {
                e.printStackTrace();
            }
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

            while (true && working) {//在一定时间内不断的尝试去拿锁（自旋），自旋的过程中要判断一下票是否已经卖完了（因为是working是一个共享资源，是不安全的，所以要加一个volatile）
                if ((System.currentTimeMillis() - currentTime) / 1000 > timeout) {//当前时间超过了设定的超时时间，竞争锁超时（因为多线程不是同步的，所以必须要判断）
                    System.out.println("---------------- try lock time out.");
                    break;
                } else {
                    //尝试获取锁
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
     * 获取锁具体实现
     * @param jedis
     * @param lockKey
     * @return
     */
    private boolean innerTryLock(Jedis jedis,String lockKey) {
        long currentTime = System.currentTimeMillis();//当前时间
        String lockTimeDuration = String.valueOf(currentTime + Lock_Timeout + 1);//锁的持续时间
        //该方法是原子的，如果key不存在，则设置当前key成功，返回1；如果当前key已经存在，则设置当前key失败，返回0。
        Long result = jedis.setnx(lockKey, lockTimeDuration);

        if (result == 1) {  //返回1 代表第1次设置
            return true;
        } else {//如果不是第一次获取则失败，判断锁是否超时
            if (checkIfLockTimeout(jedis,currentTime, lockKey)) {
                //计算newExpireTime=当前时间+过期超时时间，然后getset(lockkey, newExpireTime) 会返回当前lockkey的值currentExpireTime。
                // 判断currentExpireTime与oldExpireTime 是否相等，如果相等，说明当前getset设置成功，获取到了锁
                // 如果不相等，说明这个锁又被别的请求获取走了，那么当前请求可以直接返回失败，或者继续重试
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
        //get(lockkey)获取值oldExpireTime ，并将这个value值与当前的系统时间进行比较，如果小于当前系统时间，则认为这个锁已经超时，可以允许别的请求重新获取
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




    public static void main(String[] args) {


        //设置五个窗口同时进行卖票（起五个线程模拟售票员）
        int threadNum = 5;
        final CyclicBarrier barrier = new CyclicBarrier(threadNum);
        final CountDownLatch count = new CountDownLatch(threadNum);  // 用于统计 执行时长(等所有的子线程执行完毕之后再执行主线程)
        //需要监控不同方法的运行时间，知道性能瓶颈,利用springframework框架的工具类StopWatch可以快速方便的查看到每段代码运行的时间，准确确定性能瓶颈所在
        StopWatch watch = new StopWatch();
        watch.start();
        //设置线程启动完毕之后同时开始抢票，使用barrier
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
