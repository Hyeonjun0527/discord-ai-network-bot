import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Discord AI Assistant Dashboard",
  description: "Manage your Discord AI Assistant bot settings and statistics",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link
          rel="preconnect"
          href="https://fonts.gstatic.com"
          crossOrigin="anonymous"
        />
        <link
          href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap"
          rel="stylesheet"
        />
      </head>
      <body className="min-h-screen bg-discord-darkest text-gray-100 antialiased">
        {children}
      </body>
    </html>
  );
}
