import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.POINT;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;

import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Case&Bot stand-up minigame bot: finds the correct punchline button by reading the GameMaker runner's memory and clicks it.
 */
public class Main {

    public interface User32 extends com.sun.jna.platform.win32.User32 {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean ClientToScreen(HWND hWnd, POINT lpPoint);

        boolean IsWindow(HWND hWnd);
    }

    final WinNT.HANDLE proc;
    final HWND hwnd;

    Main(HWND hwnd, WinNT.HANDLE proc) {
        this.hwnd = hwnd;
        this.proc = proc;
    }

    /**
     * Reimplementation {@code ObjectHash_Find} (0x004620D0): looks up an object's {@code CObjectGM}.
     * <p>
     * Takes bucket {@code g_pObjectHash->pBuckets[objIndex & dwMask]} and walks {@code ObjHashNode.pNext}
     * until {@code dwKey == objIndex}.
     *
     * @param objIndex GameMaker object index from data.win object map
     * @return address of {@code CObjectGM}, or 0 if the object is not in the map
     */
    long findObject(int objIndex) {
        //game_maker global map pointer
        final int OBJ_HASH_PTR = 0x891580;
        //get address of global gm map
        long mapAddr = toAddress(readInt(OBJ_HASH_PTR));
        //deref to buckets
        long bucketsAddr = toAddress(readInt(mapAddr));
        //mask is a map address + 4 its keeping its bucket count in a mem
        int mask = readInt(mapAddr + 4);
        //node address (bucket first node address) its shift of id of object & mask * 8 bytes cuz buckets size is 8b
        long nodeAddr = toAddress(readInt(bucketsAddr + (long) (objIndex & mask) * 8));
        //going thru nodes
        while (nodeAddr != 0) {
            //object index
            if (readInt(nodeAddr + 8) == objIndex) {
                //if found needed obj get addr of it. Its node shift + 0xC = object value
                return toAddress(readInt(nodeAddr + 0xC));
            }
            //next node
            nodeAddr = toAddress(readInt(nodeAddr + 4));
        }
        return 0;
    }

    static final int OFF_OBJ_INSTANCES = 0xD0; // CObjectGM.pInstances (ObjectInstLink*)
    static final int OFF_MARKED = 0x68;        // CInstance.bMarked
    static final int OFF_DEACTIVATED = 0x69;   // CInstance.bDeactivated
    static final int OFF_ID = 0x78;            // CInstance.nId
    static final int OFF_X = 0xB4;             // CInstance.flX
    static final int OFF_Y = 0xB8;             // CInstance.flY

    /**
     * This function is inspired by ghidra revers of GM object iteration. Basically technical code how GM looks to get alive instances
     */
    Inst instancesOf(int objIndex) {
        //take object type with objIndex
        long obj = findObject(objIndex);
        if (0 == obj) {
            throw null;
        }
        //get head of object instance list. it lays of 0xD0 offset
        long link = toAddress(readInt(obj + OFF_OBJ_INSTANCES));
        //every object list can have dead links so we iterate to find alive
        while (0 != link) {
            long inst = toAddress(readInt(link + 8));
            if (0 == inst) {
                break;
            }
            boolean isNotMarked = 0 == readByte(inst + OFF_MARKED);
            boolean isNotDeactivated = 0 == readByte(inst + OFF_DEACTIVATED);
            //it needs to be not marked and not deactivated. Only alive instances.
            if (isNotMarked && isNotDeactivated) {
                //Get coords of instances. Thats why there is no pixel hunting there
                //Every instance in GM have position and we can get it by offsets x,y
                return new Inst(inst, readInt(inst + OFF_ID), readFloat(inst + OFF_X), readFloat(inst + OFF_Y));
            }
            link = toAddress(readInt(link));
        }
        return null;
    }

    /**
     * Same walk as {@link #instancesOf}, but collects every alive instance (several flowers can fly at once).
     */
    List<Inst> allInstancesOf(int objIndex) {
        List<Inst> result = new java.util.ArrayList<>();
        long obj = findObject(objIndex);
        if (0 == obj) {
            return result;
        }
        long link = toAddress(readInt(obj + OFF_OBJ_INSTANCES));
        while (0 != link) {
            long inst = toAddress(readInt(link + 8));
            if (0 == inst) {
                break;
            }
            if (0 == readByte(inst + OFF_MARKED) && 0 == readByte(inst + OFF_DEACTIVATED)) {
                result.add(new Inst(inst, readInt(inst + OFF_ID), readFloat(inst + OFF_X), readFloat(inst + OFF_Y)));
            }
            link = toAddress(readInt(link));
        }
        return result;
    }

    record Inst(long addr, int id, float x, float y) {
    }

    static final int OBJ_FLOWER = 340;          // objStandup_Flower (global.__objectNames[340])
    static final double FLOWER_LAND_Y = 220.0;  // objStandup_ITEM.finalHeight: last path point y
    static final double FLOWER_CLICK_Y = 220.0; // where we click; only mouse x matters for the bot
    static final double RECLICK_DIST = 8.0;     // re-aim only if the prediction moved this far

    record Sample(Inst inst, long tNanos) {
    }

    Map<Integer, Sample> prevFlowers = new HashMap<>();
    double lastFlowerClickX = Double.NaN;

    /**
     * Flower catch.
     * <p>
     * "Move": left mouse release sets {@code posX = mouse x}, then the bot walks there at {@code standupBotSpeed} = 5 px/frame.
     * The path is a straight line at constant speed, so two samples give velocity (vx, vy) and
     * {@code eta = (y - 220) / -vy}, {@code landX = x + vx * eta}. We click landX of the flower with the smallest eta.
     *
     * @return true if at least one flower is alive
     */
    boolean catchFlowers(Robot robot) throws InterruptedException {
        long now = System.nanoTime();
        Map<Integer, Sample> current = new HashMap<>();
        double bestEta = Double.MAX_VALUE;
        double landX = Double.NaN;

        for (Inst f : allInstancesOf(OBJ_FLOWER)) {
            current.put(f.id(), new Sample(f, now));
            Sample prev = prevFlowers.get(f.id());
            if (null == prev) {
                continue; // first time we see it, need a second sample for velocity
            }
            double dtMs = (now - prev.tNanos()) / 1e6;
            double vx = (f.x() - prev.inst().x()) / dtMs;
            double vy = (f.y() - prev.inst().y()) / dtMs;
            //flying flowers go up (y decreases); after path end they fall or splat, skip those
            if (vy >= 0 || f.y() <= FLOWER_LAND_Y) {
                continue;
            }
            double eta = (f.y() - FLOWER_LAND_Y) / -vy;
            if (eta < bestEta) {
                bestEta = eta;
                landX = f.x() + vx * eta;
            }
        }
        prevFlowers = current;

        if (Double.isNaN(landX)) {
            lastFlowerClickX = Double.NaN;
            return !current.isEmpty();
        }
        if (Double.isNaN(lastFlowerClickX) || Math.abs(landX - lastFlowerClickX) > RECLICK_DIST) {
            POINT p = roomToScreen(landX, FLOWER_CLICK_Y);
            System.out.printf("flower lands x=%.0f in %.0f ms -> click (%d,%d)%n", landX, bestEta, p.x, p.y);
            robot.mouseMove(p.x, p.y);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(20);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            lastFlowerClickX = landX;
        }
        return true;
    }


    static final double VIEW_H = 384.0;        // original scene height
    static final double ROOM_CENTER_X = 512.0; // original scene x camera position
    /**
     * Converts room coordinates to screen coordinates for the game window.
     * <p>
     * The view is 384 units high, {@code 384 * aspect} wide, centred on room x 512 and stretched over the
     * whole client area, so {@code scale = clientHeight / 384}.
     */
    POINT roomToScreen(double rx, double ry) {
        RECT rc = new RECT();
        User32.INSTANCE.GetClientRect(hwnd, rc);
        double cw = rc.right - rc.left;
        double ch = rc.bottom - rc.top;
        double scale = ch / VIEW_H;
        POINT p = new POINT(
                (int) Math.round((rx - ROOM_CENTER_X) * scale + cw / 2),
                (int) Math.round(ry * scale)
        );
        User32.INSTANCE.ClientToScreen(hwnd, p);
        return p;
    }

    static final int POLL_MS = 50;
    static final int ADVANCE_EVERY_TICKS = 12;

    static void main() throws Exception {

        final String WINDOW_TITLE = "Murder in Hotel Lisbon";

        HWND hwnd = User32.INSTANCE.FindWindow(null, WINDOW_TITLE);

        if (null == hwnd) {
            throw new IllegalStateException("Game window not found: " + WINDOW_TITLE);
        }

        IntByReference pid = new IntByReference();
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid);

        WinNT.HANDLE proc = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_VM_READ, false, pid.getValue());
        if (null == proc) {
            throw new IllegalStateException("OpenProcess failed: " + Native.getLastError());
        }

        Main bot = new Main(hwnd, proc);
        Robot robot = new Robot();
        int lastClickedId = -1;
        int idleTicks = 0;

        while (true) {

            //exit if win is gone
            if (Boolean.FALSE.equals(User32.INSTANCE.IsWindow(hwnd))) {
                System.exit(1);
            }

            //skip if win is not active
            if (Boolean.FALSE.equals(hwnd.equals(User32.INSTANCE.GetForegroundWindow()))) {
                Thread.sleep(POLL_MS);
                continue;
            }

            Inst rightAnswerInst = bot.instancesOf(
                    //id of objStandup_button_rightQuestion object in data.win
                    333
            );

            if (null != rightAnswerInst && rightAnswerInst.id() != lastClickedId) {
                Thread.sleep(300);
                //A bit fixes of coords
                POINT p = bot.roomToScreen(rightAnswerInst.x() + 30, rightAnswerInst.y() + 4);
                System.out.printf("right answer id=%d room=(%.0f,%.0f) -> screen=(%d,%d)%n", rightAnswerInst.id(), rightAnswerInst.x(), rightAnswerInst.y(), p.x, p.y);
                robot.mouseMove(p.x, p.y);
                Thread.sleep(50);
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(40);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                lastClickedId = rightAnswerInst.id();
                idleTicks = 0;
            }
            //Flowers in the air: click where the next one lands, the bot walks there by itself
            else if (bot.catchFlowers(robot)) {
                idleTicks = 0;
            }
            //Wait if there is no joke
            else if (++idleTicks >= ADVANCE_EVERY_TICKS) {
                robot.keyPress(KeyEvent.VK_ENTER);
                Thread.sleep(40);
                robot.keyRelease(KeyEvent.VK_ENTER);
                idleTicks = 0;
            }

            Thread.sleep(POLL_MS);
        }
    }


    int readInt(long addr) {
        Memory buf = new Memory(4);
        if (!Kernel32.INSTANCE.ReadProcessMemory(proc, new Pointer(addr), buf, 4, null)) {
            throw new IllegalStateException(String.format("read failed @%08X", addr));
        }
        return buf.getInt(0);
    }

    float readFloat(long addr) {
        return Float.intBitsToFloat(readInt(addr));
    }

    byte readByte(long addr) {
        Memory buf = new Memory(1);
        if (!Kernel32.INSTANCE.ReadProcessMemory(proc, new Pointer(addr), buf, 1, null)) {
            throw new IllegalStateException(String.format("read failed @%08X", addr));
        }
        return buf.getByte(0);
    }

    long toAddress(int v) {
        return Integer.toUnsignedLong(v);
    }

}
