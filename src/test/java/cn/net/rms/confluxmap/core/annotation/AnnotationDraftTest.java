package cn.net.rms.confluxmap.core.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnnotationDraftTest {
    @Test
    void rejectsGesturesSmallerThanTwoScreenPixels() {
        final AnnotationDraft line = new AnnotationDraft(AnnotationTool.LINE, new AnnotationPoint(0, 0));
        line.dragTo(new AnnotationPoint(3.9, 0), 2.0);

        assertTrue(line.geometry(2.0, true).isEmpty());

        line.dragTo(new AnnotationPoint(4.0, 0), 2.0);
        assertInstanceOf(LineAnnotationGeometry.class, line.geometry(2.0, true).orElseThrow());
    }

    @Test
    void freehandSamplesInScreenSpaceAndSimplifiesOnCommit() {
        final AnnotationDraft draft = new AnnotationDraft(
            AnnotationTool.FREEHAND, new AnnotationPoint(0, 0)
        );
        draft.dragTo(new AnnotationPoint(0.2, 0), 1.0);
        draft.dragTo(new AnnotationPoint(1, 0.01), 1.0);
        draft.dragTo(new AnnotationPoint(2, 0), 1.0);
        draft.dragTo(new AnnotationPoint(2, 2), 1.0);

        final FreehandAnnotationGeometry preview = assertInstanceOf(
            FreehandAnnotationGeometry.class, draft.geometry(1.0, false).orElseThrow()
        );
        final FreehandAnnotationGeometry committed = assertInstanceOf(
            FreehandAnnotationGeometry.class, draft.geometry(1.0, true).orElseThrow()
        );

        assertTrue(preview.points().size() > committed.points().size());
        assertEquals(new AnnotationPoint(2, 2), committed.points().get(committed.points().size() - 1));
    }
}
