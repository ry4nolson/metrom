//
//  MetromApp.swift
//  Metrom iOS
//

import SwiftUI

@main
struct MetromApp: App {
    @StateObject private var bridge = MetronomeBridge()

    var body: some Scene {
        WindowGroup {
            MetronomeView()
                .environmentObject(bridge)
                .environment(\.metromPalette, bridge.palette)
                .preferredColorScheme(bridge.colorTheme.isLight() ? .light : .dark)
        }
    }
}
