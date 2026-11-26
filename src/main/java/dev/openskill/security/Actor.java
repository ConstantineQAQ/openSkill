package dev.openskill.security;

public record Actor(String workspace, String user) {
  private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

  public static Actor current() {
    Actor a = CURRENT.get();
    if (a == null) throw new IllegalStateException("缺少执行身份");
    return a;
  }

  public static void set(Actor a) {
    CURRENT.set(a);
  }

  public static Actor optional() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }
}
