package cn.net.rms.confluxmap.mc.ui.screen;

/** Visual and click state for the structure list's aggregate checkbox. */
record StructureSelectionState(int selectedCount, int totalCount) {
    String mark() {
        if (selectedCount == 0 || totalCount == 0) {
            return "";
        }
        return selectedCount == totalCount ? "✓" : "−";
    }

    boolean visibilityAfterToggle() {
        return selectedCount != totalCount;
    }

    boolean enabled(final boolean policyAllows) {
        return policyAllows && totalCount > 0;
    }
}
