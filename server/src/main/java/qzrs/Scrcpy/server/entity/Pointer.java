/*
 * 本项目大量借鉴学习了开源投屏软件：Scrcpy，在此对该项目表示感谢
 */
package qzrs.Scrcpy.server.entity;

public final class Pointer {

  public int id;

  // 在 PointersState.update() 重建 pointerProperties 数组时被赋值为该指针在数组中的实际下标。
  // 多指手势的 ACTION_POINTER_DOWN/UP 必须以“数组下标”而非本地 id 构造 action index，二者在指针复用后并不相等。
  public int index;

  public float x;

  public float y;

  public long downTime;

  public Pointer(int id, long downTime) {
    this.id = id;
    this.downTime = downTime;
  }

}
