#!/usr/bin/env ruby
# Generates iosApp/Metrom.xcodeproj for the Metrom SwiftUI app + MetromShared framework.
require 'xcodeproj'
require 'fileutils'

ROOT = File.expand_path('..', __dir__)
IOS = File.join(ROOT, 'iosApp')
PROJECT_PATH = File.join(IOS, 'Metrom.xcodeproj')
SOURCES = File.join(IOS, 'Metrom')

FileUtils.rm_rf(PROJECT_PATH)

project = Xcodeproj::Project.new(PROJECT_PATH)
target = project.new_target(:application, 'Metrom', :ios, '16.0')
target.product_type = 'com.apple.product-type.application'

main_group = project.main_group
metrom_group = main_group.new_group('Metrom', 'Metrom')

Dir[File.join(SOURCES, '*.swift')].sort.each do |path|
  name = File.basename(path)
  ref = metrom_group.new_file(name)
  target.source_build_phase.add_file_reference(ref)
end

tones_ref = metrom_group.new_file('Resources/tones')
tones_ref.name = 'tones'
tones_ref.last_known_file_type = 'folder'
tones_ref.include_in_index = '0'
target.resources_build_phase.add_file_reference(tones_ref)

assets_ref = metrom_group.new_file('Assets.xcassets')
target.resources_build_phase.add_file_reference(assets_ref)

metrom_group.new_file('Info.plist')

# Ensure MetromShared exists for this SDK. Static K/N framework: link only — do not embed
# (App Store rejects MetromShared.framework/MetromShared as a standalone binary in Frameworks/).
phase = project.new(Xcodeproj::Project::Object::PBXShellScriptBuildPhase)
phase.name = 'Compile MetromShared'
phase.shell_script = <<~'SCRIPT'
  set -euo pipefail
  export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || true)}"
  cd "$SRCROOT/.."

  if [[ "${PLATFORM_NAME:-}" == *simulator* ]]; then
    FRAME="$SRCROOT/../shared/build/bin/iosSimulatorArm64/debugFramework/MetromShared.framework"
    GRADLE_TASK=":shared:linkDebugFrameworkIosSimulatorArm64"
  elif [[ "${CONFIGURATION:-}" == "Release" ]]; then
    FRAME="$SRCROOT/../shared/build/bin/iosArm64/releaseFramework/MetromShared.framework"
    GRADLE_TASK=":shared:linkReleaseFrameworkIosArm64"
  else
    FRAME="$SRCROOT/../shared/build/bin/iosArm64/debugFramework/MetromShared.framework"
    GRADLE_TASK=":shared:linkDebugFrameworkIosArm64"
  fi

  ./gradlew -p . "$GRADLE_TASK"
  if [[ ! -d "$FRAME" ]]; then
    echo "error: MetromShared.framework missing at $FRAME" >&2
    exit 1
  fi
  echo "Using $FRAME (link only — not embedded)"
SCRIPT
phase.shell_path = '/bin/bash'
phase.always_out_of_date = '1'
target.build_phases.delete(phase)
target.build_phases.unshift(phase)

shared_settings = {
  'PRODUCT_BUNDLE_IDENTIFIER' => 'com.metrom.app',
  'PRODUCT_NAME' => 'Metrom',
  'INFOPLIST_FILE' => 'Metrom/Info.plist',
  'GENERATE_INFOPLIST_FILE' => 'NO',
  'ASSETCATALOG_COMPILER_APPICON_NAME' => 'AppIcon',
  'SWIFT_VERSION' => '5.0',
  'TARGETED_DEVICE_FAMILY' => '1,2',
  'IPHONEOS_DEPLOYMENT_TARGET' => '16.0',
  'SDKROOT' => 'iphoneos',
  'CODE_SIGN_STYLE' => 'Automatic',
  'DEVELOPMENT_TEAM' => 'M3C8F32WA5',
  'FRAMEWORK_SEARCH_PATHS[sdk=iphonesimulator*]' =>
    '$(inherited) "$(SRCROOT)/../shared/build/bin/iosSimulatorArm64/debugFramework"',
  'FRAMEWORK_SEARCH_PATHS[sdk=iphoneos*]' =>
    '$(inherited) "$(SRCROOT)/../shared/build/bin/iosArm64/releaseFramework" "$(SRCROOT)/../shared/build/bin/iosArm64/debugFramework"',
  'OTHER_LDFLAGS' => '$(inherited) -framework MetromShared',
  'ENABLE_PREVIEWS' => 'NO',
  'ENABLE_DEBUG_DYLIB' => 'NO',
  'CURRENT_PROJECT_VERSION' => '1',
  'MARKETING_VERSION' => '1.0',
  'LD_RUNPATH_SEARCH_PATHS' => '$(inherited) @executable_path/Frameworks',
  'ALWAYS_SEARCH_USER_PATHS' => 'NO',
  'CLANG_ENABLE_MODULES' => 'YES',
}

target.build_configurations.each do |config|
  shared_settings.each { |k, v| config.build_settings[k] = v }
  if config.name == 'Release'
    config.build_settings['CODE_SIGN_STYLE'] = 'Manual'
    config.build_settings['CODE_SIGN_IDENTITY'] = 'Apple Distribution'
    config.build_settings['CODE_SIGN_IDENTITY[sdk=iphoneos*]'] = 'Apple Distribution'
    config.build_settings['PROVISIONING_PROFILE_SPECIFIER'] = 'Metrom App Store'
  end
end

project.build_configurations.each do |config|
  config.build_settings['IPHONEOS_DEPLOYMENT_TARGET'] = '16.0'
  config.build_settings['SDKROOT'] = 'iphoneos'
end

project.save

scheme = Xcodeproj::XCScheme.new
scheme.add_build_target(target)
scheme.set_launch_target(target)
scheme.save_as(PROJECT_PATH, 'Metrom', true)

puts "Wrote #{PROJECT_PATH}"
puts "Swift: #{Dir[File.join(SOURCES, '*.swift')].size}, tones + AppIcon assets"
