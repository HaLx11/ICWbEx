package mrtjp.core.vec;

public class Size {
    public Size(int w, int h) {
    }

    public int width() {
        return 0;
    }

    public int height() {
        return 0;
    }

    /**
     * 0.3.9 (NewICNode): PR 的"新建 IC"对话框把 sizer 的单位换算成格数时用的就是它
     * （反汇编 GuiICWorkbench$$anonfun$onAddedToParent_Impl$8$$anonfun$apply$mcV$sp$1:
     * bipush 16 + invokevirtual Size.$times(I)Lmrtjp/core/vec/Size;）。
     */
    public Size $times(int factor) {
        return null;
    }
}
