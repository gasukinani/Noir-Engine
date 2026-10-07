#include "ShaderAsset.h"

namespace noir::render {

// ShaderAsset is intentionally allocation-free during rendering. Text and SPIR-V are
// loaded/compiled once during asset preparation or backend warm-up and then only read.
} // namespace noir::render
