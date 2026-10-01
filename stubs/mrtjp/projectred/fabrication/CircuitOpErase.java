package mrtjp.projectred.fabrication;

/**
 * 0.4.3 signature stub (PR 4.12.44); the real class is used at runtime.
 *
 * <p>原版工作台工具栏里的**擦除**工具。反汇编 {@code CircuitOpDefs$} 的静态初始化可见
 * {@code Erase = new OpDef(new CircuitOpErase())}，而 {@code CircuitOpErase.getOpName()}
 * 返回常量 {@code "Erase"}。
 *
 * <p>ICWbEx 用 {@code instanceof CircuitOpErase} 判断"玩家当前选的是原版擦除"，
 * 从而把交互接管成安全删除（详见 {@code GuiICWbEx.eraserMode}）。**用类型而不是字符串判断**：
 * 名字是给人看的，PR 改文案不影响这里。
 */
public class CircuitOpErase implements CircuitOp {
    @Override
    public int id() {
        return 0;
    }

    @Override
    public String getOpName() {
        return "Erase";
    }

    @Override
    public void writeOp(IntegratedCircuit ic, mrtjp.core.vec.Point start, mrtjp.core.vec.Point end,
            codechicken.lib.data.MCDataOutput out) {
    }
}
