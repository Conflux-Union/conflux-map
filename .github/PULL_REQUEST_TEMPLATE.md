<!--
Pull requests are squash-merged; see CONTRIBUTING.md for the full guide.
-->

## Summary

<!-- What changed and why. Reference the issue, e.g. "Fixes #123". -->

## Verification

<!-- What you ran and what passed. Match the depth to the change:
     ./gradlew :common:test                    shared core policy
     ./gradlew :<version>:test                 Fabric versions you touched
     ./gradlew :paper:check                    Paper adapter
     ./gradlew :1.21.4:compileGametestJava     after renaming types shared with src/gametest
-->

-

## Version impact

<!-- Which Minecraft versions can this change affect? Mention version-gated code, mixins, or render paths. CI builds the full matrix and must pass. -->

## UI changes

<!-- For render/UI changes, attach before/after screenshots or a short clip. Delete this section otherwise. -->

## Checklist

- [ ] `common/` stays Minecraft-free — no platform imports, no preprocessor directives
- [ ] Behavior changes come with focused tests, or the reason is explained above
- [ ] Third-party code or assets are recorded in `THIRD_PARTY_NOTICES.md`
