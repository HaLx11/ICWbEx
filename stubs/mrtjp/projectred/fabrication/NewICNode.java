package mrtjp.projectred.fabrication;

import mrtjp.core.gui.TNode;
import mrtjp.core.vec.Size;
import scala.Function0;

/**
 * 0.3.9 signature stub (SRG/PR names); the real PR 4.12.44 class is used at runtime.
 *
 * <p>PR 的 IC 工作台里那个"新建 IC"对话框（选尺寸 + 输名字）。反汇编
 * {@code GuiICWorkbench$$anonfun$onAddedToParent_Impl$8} 得到原版用法：
 * <pre>
 *   if (tile.hasBP()) {                        // 只有插了 IC 蓝图/板才会出现
 *       NewICNode nic = new NewICNode();
 *       nic.position_$eq(...);
 *       nic.completionDelegate_$eq(() -> {     // 用户点确定
 *           IntegratedCircuit ic = new IntegratedCircuit();
 *           ic.name_$eq(nic.getName());
 *           ic.size_$eq(nic.selectedBoardSize().$times(16));   // 单位是 16 格
 *           tile.sendNewICToServer(ic);        // ★ 原始整板 desc
 *       });
 *       addChild(nic);
 *   }
 * </pre>
 * 最后那一步就是 2026-10-01 11:51 崩服的来源：整板 desc 会被服务器回显，
 * 回显把客户端板整块替换（可能替换成空板），而服务器那一刻还在为旧板发逐元件
 * 状态帧 ⇒ 空格子 → PR 兜底 subID=0 ⇒ 序列门 key>10 ⇒ 断连。
 * ICWbEx 因此接管了 {@code completionDelegate}（见 GuiICWbEx.addChild 的重写），
 * 让这个对话框走我们的两阶段路径。字段/方法可见性照 PR javap 抄写：
 * {@code selectedBoardSize()/completionDelegate()/getName()} 都是 public。
 */
public class NewICNode implements TNode {
    public NewICNode() {
    }

    /** 用户选的板子尺寸，单位是 16 格（原版自己会再 {@code $times(16)}）。 */
    public Size selectedBoardSize() {
        return null;
    }

    public void selectedBoardSize_$eq(Size size) {
    }

    /** 用户输入的名字（原版直接塞给 {@code IntegratedCircuit.name_$eq}）。 */
    public String getName() {
        return null;
    }

    public Function0 completionDelegate() {
        return null;
    }

    public void completionDelegate_$eq(Function0 delegate) {
    }

    // ---- TNode 的最小集合（本模组只需要能把它从 addChild 里认出来） ----
    @Override
    public TNode parent() {
        return null;
    }

    @Override
    public mrtjp.core.vec.Point position() {
        return null;
    }

    @Override
    public boolean isRoot() {
        return false;
    }

    @Override
    public mrtjp.core.vec.Point convertPointFromScreen(mrtjp.core.vec.Point point) {
        return null;
    }

    @Override
    public boolean rayTest(mrtjp.core.vec.Point point) {
        return false;
    }

    @Override
    public void addChild(TNode node) {
    }
}
