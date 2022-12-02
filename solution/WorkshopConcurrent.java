package cp2022.solution;

import cp2022.base.Workplace;
import cp2022.base.WorkplaceId;
import cp2022.base.Workshop;

import java.util.*;
import java.util.concurrent.*;

public class WorkshopConcurrent implements Workshop {
    private volatile int population;
    private final Map<WorkplaceId, WorkplaceWrapper> workplaces; // żeby móc cokolwiek zwrócić przy enter i switchTo
    private final ConcurrentMap<WorkplaceId, WorkplaceId> transferWishes; // do szukania cyklu i wpuszczania wątków do stanowisk
    private final Map<Long, WorkplaceId> workers;
    private final Semaphore mutex;
    private final Semaphore populationLimit;
    private volatile boolean isCycle;

    public WorkshopConcurrent(Collection<Workplace> workplaces) {
        this.workplaces = new HashMap<>();
        for(var v: workplaces) {
            this.workplaces.put(v.getId(), new WorkplaceWrapper(v));
        }
        this.transferWishes = new ConcurrentHashMap<>();
        this.mutex = new Semaphore(1);
        this.populationLimit = new Semaphore(2*this.workplaces.size(), true);
        this.workers = new HashMap<>();
        this.population = 2*this.workplaces.size();
        this.isCycle = false;
    }

    private void acquire(Semaphore s) {
        try {
            s.acquire();
        } catch (InterruptedException e) {
            throw new RuntimeException("panic: unexpected thread interruption");
        }
    }

    public Workplace enter(WorkplaceId wid) {
        WorkplaceWrapper newWorkplace = workplaces.get(wid);
        acquire(populationLimit);
        acquire(mutex);
        if (newWorkplace.occupied) {
            mutex.release();
            acquire(newWorkplace.waitToEnter);
            //acquire(mutex);
        }
        newWorkplace.occupied = true;
        workers.put(Thread.currentThread().getId(), wid);
        mutex.release();
        return workplaces.get(wid);
    }

    private int cycleLength(WorkplaceId start) {
        int result = 1;
        WorkplaceId tmp = transferWishes.get(start);
        while (tmp != start && transferWishes.containsKey(tmp)) {
            tmp = transferWishes.get(tmp);
            result++;
        }
        if (tmp == start) return result;
        return 0;
    }

    public Workplace switchTo(WorkplaceId wid) {
        boolean giveMutexBack = false;
        System.out.println("zaczynam switchto powinno być 0 lub 1 " + mutex);
        acquire(mutex);
        int length = 0;
        WorkplaceWrapper newWorkplace = workplaces.get(wid);
        WorkplaceId oldId = workers.get(Thread.currentThread().getId());
        WorkplaceWrapper oldWorkplace = workplaces.get(oldId);
        if (wid == oldId) {
            System.out.println("podnoszę mutexa " + mutex + Thread.currentThread().getName());
            mutex.release();
            return newWorkplace;
        }
        if (!newWorkplace.occupied) System.out.println("nikogo nie ma wpierdalam się " + Thread.currentThread().getName());
        if (newWorkplace.occupied) {
            transferWishes.put(oldId, wid);
            newWorkplace.queue.add(oldId);
            length = cycleLength(oldId);
//            CountDownLatch latch = new CountDownLatch(2);
//            newWorkplace.waitForNext = latch;
//            oldWorkplace.waitForPrev = latch;
            if (length > 0) {
                isCycle = true;
                CountDownLatch waitForCycle = new CountDownLatch(length);
                System.out.println("znalazłlem cykl " + waitForCycle.toString() + Thread.currentThread().getName());
                WorkplaceWrapper whereIAm = newWorkplace;
                WorkplaceWrapper whereIGo;
                newWorkplace.queue.remove(oldWorkplace.getId());
                newWorkplace.waitForPrev = null;
                newWorkplace.waitForNext = waitForCycle;
                System.out.println("ustawiam " + newWorkplace.waitForNext + Thread.currentThread().getName());
                while (whereIAm != oldWorkplace) {
                    whereIGo = workplaces.get(transferWishes.get(whereIAm.getId()));
                    whereIGo.queue.remove(whereIAm.getId());
                    whereIGo.waitForPrev = null;
                    whereIGo.waitForNext = waitForCycle;
                    System.out.println("czekam na mutexa " + mutex);
                    whereIAm.waitForSwitch.release(); //oddaje tego zjeba mutexa
                    whereIAm = whereIGo;
                    acquire(mutex); // tu trzeba zamienic mutex na inny semafor cycle albo zabawa z isCycle
                }
            }
            else {
                mutex.release();
                System.out.println("chuj jebany " + Thread.currentThread().getName());
                acquire(oldWorkplace.waitForSwitch);
                System.out.println("jednak nie chuj " + Thread.currentThread().getName());
            }
//            if(!isCycle) { // normalne zwolnienie stanowiska (bez cyklu)
//                CountDownLatch latch = new CountDownLatch(2);
//                newWorkplace.waitForNext = latch;
//                oldWorkplace.waitForPrev = latch;
//            }
            transferWishes.remove(oldId);
        }
        workers.remove(Thread.currentThread().getId());
        workers.put(Thread.currentThread().getId(), wid);
        if (!isCycle) { // nie ma cyklu
            if (newWorkplace.occupied) {
                CountDownLatch latch = new CountDownLatch(2);
                oldWorkplace.waitForNext = latch;
                newWorkplace.waitForPrev = latch;
            }
            if (!oldWorkplace.queue.isEmpty()) {
                WorkplaceId workerToPass = oldWorkplace.queue.remove(0);
                workplaces.get(workerToPass).waitForSwitch.release();
            }
            else if (oldWorkplace.waitToEnter.getQueueLength() > 0) {
                oldWorkplace.waitToEnter.release();
            }
            else {
                oldWorkplace.occupied = false;
                System.out.println("podnoszę mutexa " + mutex + Thread.currentThread().getName());
                mutex.release();
            }
        } else {
            if (length > 0) {
                System.out.println("koniec cyklu " + Thread.currentThread().getName());
                isCycle = false;
            }
            mutex.release();
        }
        newWorkplace.occupied = true;
        return newWorkplace;
    }

    public void leave() {
        acquire(mutex);
        workers.remove(Thread.currentThread());
        population--;
        if(population == 0) {
            for (int i = 0; i < 2 * workplaces.size(); i++) {
                populationLimit.release();
            }
            population = 2 * workplaces.size();
        }
        WorkplaceWrapper myWorkplace = workplaces.get(workers.get(Thread.currentThread().getId()));
        if (!myWorkplace.queue.isEmpty()) {
            WorkplaceId workerToPass = myWorkplace.queue.remove(0);
            System.out.println("oddaję sekcję krytyczną " + mutex + Thread.currentThread().getName());
            workplaces.get(workerToPass).waitForSwitch.release();
        }
        else if (myWorkplace.waitToEnter.getQueueLength() > 0) {
            myWorkplace.waitToEnter.release();
        }
        else {
            myWorkplace.occupied = false;
            mutex.release();
        }
    }
}