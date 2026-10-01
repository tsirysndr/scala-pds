import { setupI18n } from "../i18n";

// Tests assert user-visible English text, so the fixture language is pinned.
setupI18n("en");

import "@testing-library/jest-dom/vitest";

// happy-dom's Web Animations API rejects on cancel, which Framer Motion does
// during unmount. Removing it makes motion fall back to its timer path.
Reflect.deleteProperty(Element.prototype, "animate");
