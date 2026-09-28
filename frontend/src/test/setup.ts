import "@testing-library/jest-dom/vitest";

// happy-dom's Web Animations API rejects on cancel, which Framer Motion does
// during unmount. Removing it makes motion fall back to its timer path.
Reflect.deleteProperty(Element.prototype, "animate");
