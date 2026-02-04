# JS Block Runner

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![IntelliJ IDEA](https://img.shields.io/badge/IntelliJ%20IDEA-2025.1+-blue.svg)](https://www.jetbrains.com/idea/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-purple.svg)](https://kotlinlang.org/)

An IntelliJ IDEA plugin that lets you execute JavaScript or TypeScript code snippets directly from your editor with intelligent mocking support. Perfect for quick testing, debugging, and exploring code behavior without setting up test files or running your entire application.

## ✨ Features

- **Run Any Code Block** - Execute selected code, functions, or the function under your cursor
- **Smart Mock Detection** - Automatically detects external dependencies that need mocking
- **Interactive Mock Editor** - Configure mock values with a user-friendly dialog
- **TypeScript Support** - Automatically strips type annotations for Node.js execution
- **Debug Mode** - Attach IntelliJ or Chrome DevTools debugger to step through code
- **Constructor Mocking** - Properly handles `new` keyword with spy constructors
- **Async/Await Support** - Full support for async functions and await expressions
- **`this.` References** - Mock `this.property` and `this.method()` calls

## 🚀 Quick Start

### Installation

1. Download the plugin from the [JetBrains Marketplace](https://plugins.jetbrains.com/) (or build from source)
2. Install via **Settings → Plugins → Install Plugin from Disk**
3. Restart IntelliJ IDEA

### Usage

1. Place your cursor inside a function or select a code block
2. Right-click and select **"Run JS Block with Mocks"** (or press `Ctrl+Alt+R`)
3. Configure function arguments and mock values in the dialog
4. Click **Run** to execute in Node.js

## 📋 Requirements

- **Node.js** - Installed and available in PATH
- **IntelliJ IDEA Ultimate** - Version 2025.1 or later
- **JavaScript Plugin** - Bundled with IntelliJ IDEA Ultimate

## 🎯 Use Cases

| Scenario | Description |
|----------|-------------|
| **Quick Function Testing** | Test a single function without running the entire application |
| **Input Exploration** | Explore how a code snippet behaves with different inputs |
| **Dependency Mocking** | Debug complex logic by mocking external dependencies |
| **TypeScript Validation** | Validate TypeScript code execution without compilation setup |
| **Rapid Prototyping** | Quick prototyping and experimentation |

## 🔧 Mock Examples

The plugin auto-detects dependencies and suggests mocks. You can provide:

```javascript
// Simple values
"test"
42
true
null

// Objects
({ id: 1, name: "test" })

// Arrays
[1, 2, 3]

// Functions
() => "mocked result"

// Async functions
async () => ({ data: "response" })

// Constructors
class { constructor(x) { this.x = x; } }
or even a simple function:
function(x) { this.x = x; }
```

## 🐛 Debug Mode

Enable the debug checkbox to pause execution and attach a debugger:

### IntelliJ IDEA
1. Enable debug mode in the Run dialog
2. Go to **Run → Attach to Node.js/Chrome**
3. Connect to `localhost:9229`

### Chrome DevTools
1. Enable debug mode in the Run dialog
2. Open `chrome://inspect` in Chrome
3. Click "inspect" on the remote target

> 💡 **Tip:** Add `debugger;` statements in your code to set breakpoints.

## 🛠️ Building from Source

### Prerequisites

- JDK 21 or later
- Gradle (wrapper included)

### Build Commands

```bash
# Clone the repository
git clone https://github.com/rajivneupane/js-block-Runner.git
cd js-block-Runner

# Build the plugin
./gradlew build

# Run in a sandbox IDE for testing
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin
```

The built plugin will be located in `build/distributions/`.

## 📁 Project Structure

```
js-block-Runner/
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── rn/jsblockrunner/
│       │       ├── RunJsBlockWithMocksAction.kt  # Main action entry point
│       │       ├── RunDialog.kt                   # Execution dialog UI
│       │       ├── MockValuesDialog.kt            # Mock configuration dialog
│       │       ├── JsPsiHelper.kt                 # JavaScript PSI utilities
│       │       ├── RunnerGenerator.kt             # Code generation for runner
│       │       └── NodeLocator.kt                 # Node.js path detection
│       └── resources/
│           └── META-INF/
│               └── plugin.xml                     # Plugin configuration
├── build.gradle.kts                               # Gradle build configuration
├── gradle.properties                              # Gradle properties
└── LICENSE                                        # MIT License
```

## ⌨️ Keyboard Shortcuts

| Action | Shortcut |
|--------|----------|
| Run JS Block with Mocks | `Ctrl+Alt+R` |

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 👤 Author

**Rajiv Neupane**

- GitHub: [@rajivneupane](https://github.com/rajivneupane)

---

<p align="center">
  Made with ❤️ for the JavaScript/TypeScript developer community
</p>
