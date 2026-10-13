using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Web.Script.Serialization;
using System.Windows.Automation;

namespace CallGuard.PhoneLinkUi
{
    internal static class PhoneLinkUiProbe
    {
        private const string TargetProcessName = "PhoneExperienceHost";

        private sealed class ActionRule
        {
            public string Action { get; private set; }
            public string[] Labels { get; private set; }
            public ActionRule(string action, params string[] labels) { Action = action; Labels = labels; }
        }

        private static readonly ActionRule[] ActionRules =
        {
            new ActionRule("answer", "接听", "接听电话", "answer", "accept", "accept call"),
            new ActionRule("reject", "拒绝", "拒接", "拒绝来电", "decline", "reject", "reject call"),
            new ActionRule("hangup", "挂断", "结束", "结束通话", "hang up", "end call"),
            new ActionRule("unmute", "取消静音", "unmute"),
            new ActionRule("mute", "静音", "mute")
        };

        public static int Main(string[] args)
        {
            try
            {
                string outputPath = ReadOutputPath(args);
                Dictionary<string, object> report = Inspect();
                string json = new JavaScriptSerializer().Serialize(report);
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outputPath)));
                File.WriteAllText(outputPath, json + Environment.NewLine, new UTF8Encoding(false));
                Console.WriteLine(json);
                return (string)report["status"] == "ok" ? 0 : 2;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine(ex.GetType().Name + ": " + ex.Message);
                return 1;
            }
        }

        private static string ReadOutputPath(string[] args)
        {
            for (int i = 0; i < args.Length - 1; i++)
            {
                if (args[i] == "--output") return args[i + 1];
            }
            throw new ArgumentException("Usage: PhoneLinkUiProbe.exe --output <report.json>");
        }

        private static Dictionary<string, object> Inspect()
        {
            Process[] processes = Process.GetProcessesByName(TargetProcessName);
            Dictionary<string, object> report = NewBaseReport();
            report["matching_process_count"] = processes.Length;

            if (processes.Length != 1)
            {
                report["status"] = processes.Length == 0 ? "process_not_found" : "ambiguous_processes";
                report["matching_window_count"] = 0;
                report["actions"] = new object[0];
                report["interactive_summary"] = new object[0];
                return report;
            }

            Process process = processes[0];
            AutomationElementCollection windows = AutomationElement.RootElement.FindAll(
                TreeScope.Children,
                new PropertyCondition(AutomationElement.ProcessIdProperty, process.Id));
            report["matching_window_count"] = windows.Count;
            if (windows.Count == 0)
            {
                report["status"] = "window_not_found";
                report["actions"] = new object[0];
                report["interactive_summary"] = new object[0];
                return report;
            }

            Condition interactiveControls = new OrCondition(
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Button),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.MenuItem),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.Hyperlink),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.CheckBox),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.RadioButton),
                new PropertyCondition(AutomationElement.ControlTypeProperty, ControlType.TabItem));
            List<Dictionary<string, object>> descriptors = new List<Dictionary<string, object>>();
            List<Dictionary<string, object>> actions = new List<Dictionary<string, object>>();
            Dictionary<string, int> counts = new Dictionary<string, int>(StringComparer.Ordinal);
            int interactiveCount = 0;

            for (int windowIndex = 0; windowIndex < windows.Count; windowIndex++)
            {
                AutomationElementCollection elements = windows[windowIndex].FindAll(
                    TreeScope.Descendants, interactiveControls);
                interactiveCount += elements.Count;
                for (int i = 0; i < elements.Count; i++)
                {
                    AutomationElement element = elements[i];
                    string type = SafeControlType(element);
                    counts[type] = counts.ContainsKey(type) ? counts[type] + 1 : 1;
                    string name = SafeCurrent(element, AutomationElement.NameProperty);
                    string action = ClassifyAction(name);
                    descriptors.Add(new Dictionary<string, object>
                    {
                        { "window_index", windowIndex },
                        { "element_index", i },
                        { "control_type", type },
                        { "enabled", SafeBool(element, AutomationElement.IsEnabledProperty) },
                        { "offscreen", SafeBool(element, AutomationElement.IsOffscreenProperty) },
                        { "supports_invoke", SupportsInvoke(element) },
                        { "has_accessible_name", !string.IsNullOrWhiteSpace(name) }
                    });
                    if (action == null) continue;

                    string automationId = SafeCurrent(element, AutomationElement.AutomationIdProperty);
                    string className = SafeCurrent(element, AutomationElement.ClassNameProperty);
                    actions.Add(new Dictionary<string, object>
                    {
                        { "action", action },
                        { "control_type", type },
                        { "enabled", SafeBool(element, AutomationElement.IsEnabledProperty) },
                        { "offscreen", SafeBool(element, AutomationElement.IsOffscreenProperty) },
                        { "supports_invoke", SupportsInvoke(element) },
                        { "window_index", windowIndex },
                        { "element_key", Fingerprint(type, automationId, className, name) }
                    });
                }
            }

            report["status"] = "ok";
            report["process_id"] = process.Id;
            report["interactive_element_count"] = interactiveCount;
            report["interactive_summary"] = counts.OrderBy(pair => pair.Key)
                .Select(pair => new Dictionary<string, object>
                {
                    { "control_type", pair.Key },
                    { "count", pair.Value }
                }).ToArray();
            report["interactive_elements"] = descriptors.ToArray();
            report["actions"] = actions.ToArray();
            report["privacy"] = "Raw UI names, phone numbers, contact names, automation IDs and class names are not written.";
            return report;
        }
        private static Dictionary<string, object> NewBaseReport()
        {
            return new Dictionary<string, object>
            {
                { "schema", "callguard.phone_link_ui_probe.v1" },
                { "captured_at_utc", DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture) },
                { "target_process", TargetProcessName },
                { "mode", "read_only" }
            };
        }

        private static string ClassifyAction(string value)
        {
            string normalized = Regex.Replace((value ?? string.Empty).Trim().ToLowerInvariant(), @"\s+", " ");
            if (normalized.Length == 0) return null;
            foreach (ActionRule rule in ActionRules)
            {
                if (rule.Labels.Any(label => normalized == label || normalized.Contains(label))) return rule.Action;
            }
            return null;
        }

        private static string Fingerprint(params string[] values)
        {
            using (SHA256 sha = SHA256.Create())
            {
                byte[] digest = sha.ComputeHash(Encoding.UTF8.GetBytes(string.Join("\u001f", values)));
                return BitConverter.ToString(digest, 0, 8).Replace("-", string.Empty).ToLowerInvariant();
            }
        }

        private static bool SupportsInvoke(AutomationElement element)
        {
            object pattern;
            return element.TryGetCurrentPattern(InvokePattern.Pattern, out pattern);
        }

        private static bool SafeBool(AutomationElement element, AutomationProperty property)
        {
            object value = SafeCurrentObject(element, property);
            return value is bool && (bool)value;
        }

        private static string SafeControlType(AutomationElement element)
        {
            object value = SafeCurrentObject(element, AutomationElement.ControlTypeProperty);
            ControlType type = value as ControlType;
            return type == null ? "Unknown" : type.ProgrammaticName.Replace("ControlType.", string.Empty);
        }

        private static string SafeCurrent(AutomationElement element, AutomationProperty property)
        {
            object value = SafeCurrentObject(element, property);
            return value == null ? string.Empty : Convert.ToString(value, CultureInfo.InvariantCulture);
        }

        private static object SafeCurrentObject(AutomationElement element, AutomationProperty property)
        {
            try
            {
                object value = element.GetCurrentPropertyValue(property, true);
                return value == AutomationElement.NotSupported ? null : value;
            }
            catch (ElementNotAvailableException) { return null; }
        }
    }
}
