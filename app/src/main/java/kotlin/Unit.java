package kotlin;

/** 内核会取 kotlin.Unit.INSTANCE 作为回调值，这里给出同名占位。 */
public final class Unit {
    public static final Unit INSTANCE = new Unit();

    private Unit() {}

    @Override
    public String toString() {
        return "kotlin.Unit";
    }
}
