import SwiftUI
import WebKit

@main
struct TradeBookApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @AppStorage("serverURL") private var serverURL = ""
    @State private var urlText = ""

    var body: some View {
        Group {
            if let url = URL(string: normalizedURL), !serverURL.isEmpty {
                WebView(url: url)
                    .ignoresSafeArea(.all)
            } else {
                VStack(spacing: 18) {
                    Text("매출표").font(.largeTitle.bold())
                    Text("NAS 서버 주소를 입력하세요")
                    TextField("http://192.168.0.10:8080", text: $urlText)
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                    Button("연결") {
                        serverURL = urlText.trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                    .buttonStyle(.borderedProminent)
                }
                .padding(28)
            }
        }
        .onAppear { urlText = serverURL }
    }

    private var normalizedURL: String {
        serverURL.hasSuffix("/") ? String(serverURL.dropLast()) : serverURL
    }
}

struct WebView: UIViewRepresentable {
    let url: URL
    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let view = WKWebView(frame: .zero, configuration: config)
        view.allowsBackForwardNavigationGestures = true
        view.load(URLRequest(url: url))
        return view
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
