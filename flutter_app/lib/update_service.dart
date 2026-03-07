import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';
import 'main.dart'; // To access T class

class GithubUpdateService {
  static const String _owner = 'thumb2086';
  static const String _repo = 'Device-Linker';
  static const String _apiUrl = 'https://api.github.com/repos/$_owner/$_repo/releases/latest';

  Future<void> checkForUpdates(BuildContext context) async {
    try {
      final response = await http.get(Uri.parse(_apiUrl));
      if (response.statusCode != 200) return;

      final data = jsonDecode(response.body);
      final String latestTagName = data['tag_name'] ?? ''; // e.g., "v1.5.0"
      final String htmlUrl = data['html_url'] ?? '';

      if (latestTagName.isEmpty) return;

      final packageInfo = await PackageInfo.fromPlatform();
      final String currentVersion = packageInfo.version; // e.g., "1.4.0"

      if (_isUpdateAvailable(currentVersion, latestTagName)) {
        if (!context.mounted) return;
        _showUpdateDialog(context, latestTagName, htmlUrl);
      }
    } catch (e) {
      debugPrint('Update check failed: $e');
    }
  }

  bool _isUpdateAvailable(String current, String latest) {
    // Basic semver comparison
    // Normalizing: remove 'v' prefix if present
    String cleanCurrent = current.startsWith('v') ? current.substring(1) : current;
    String cleanLatest = latest.startsWith('v') ? latest.substring(1) : latest;

    List<int> currentParts = cleanCurrent.split('.').map((e) => int.tryParse(e) ?? 0).toList();
    List<int> latestParts = cleanLatest.split('.').map((e) => int.tryParse(e) ?? 0).toList();

    // Pad with zeros if necessary
    while (currentParts.length < 3) {
      currentParts.add(0);
    }
    while (latestParts.length < 3) {
      latestParts.add(0);
    }

    for (int i = 0; i < 3; i++) {
      if (latestParts[i] > currentParts[i]) return true;
      if (latestParts[i] < currentParts[i]) return false;
    }

    return false;
  }

  void _showUpdateDialog(BuildContext context, String version, String url) {
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: Text(T.of(context, 'update_available')),
        content: Text(T.of(context, 'update_desc', [version])),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(T.of(context, 'update_later')),
          ),
          FilledButton(
            onPressed: () async {
              final uri = Uri.parse(url);
              if (await canLaunchUrl(uri)) {
                await launchUrl(uri, mode: LaunchMode.externalApplication);
              }
              if (context.mounted) Navigator.pop(context);
            },
            child: Text(T.of(context, 'update_now')),
          ),
        ],
      ),
    );
  }
}
